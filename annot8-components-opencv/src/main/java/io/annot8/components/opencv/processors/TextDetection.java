/* Annot8 (annot8.io) - Licensed under Apache-2.0. */
package io.annot8.components.opencv.processors;

import io.annot8.api.capabilities.Capabilities;
import io.annot8.api.components.annotations.ComponentDescription;
import io.annot8.api.components.annotations.ComponentName;
import io.annot8.api.components.annotations.SettingsClass;
import io.annot8.api.components.responses.ProcessorResponse;
import io.annot8.api.context.Context;
import io.annot8.api.data.Item;
import io.annot8.api.settings.Description;
import io.annot8.common.components.AbstractProcessor;
import io.annot8.common.components.AbstractProcessorDescriptor;
import io.annot8.common.components.capabilities.SimpleCapabilities;
import io.annot8.common.data.content.Image;
import io.annot8.components.opencv.utils.OpenCVUtils;
import io.annot8.conventions.PropertyKeys;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfRotatedRect;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.Imgproc;
import org.opencv.utils.Converters;

@ComponentName("Text Detection")
@ComponentDescription(
    "Detect within an image using the EAST algorithm, and extract text into separate images")
@SettingsClass(TextDetection.Settings.class)
public class TextDetection
    extends AbstractProcessorDescriptor<TextDetection.Processor, TextDetection.Settings> {
  @Override
  public Capabilities capabilities() {
    return new SimpleCapabilities.Builder()
        .withProcessesContent(Image.class)
        .withCreatesContent(Image.class)
        .build();
  }

  @Override
  protected Processor createComponent(Context context, Settings settings) {
    return new Processor(settings);
  }

  public static class Processor extends AbstractProcessor {
    private final Settings settings;
    private final Net eastNet;

    static {
      nu.pattern.OpenCV.loadLocally();
    }

    public Processor(Settings settings) {
      this.settings = settings;
      eastNet = Dnn.readNetFromTensorflow(settings.getEastModel().toString());
    }

    @Override
    public ProcessorResponse process(Item item) {
      List<Exception> exceptions = new ArrayList<>();

      // Snapshot the Image content, so we don't recursively end up processing images
      List<Image> images = item.getContents(Image.class).collect(Collectors.toList());

      images.forEach(
          img -> {
            try {
              // Process image
              log().debug("Processing image {}", img.getId());

              long start = System.currentTimeMillis();
              processImage(item, img);
              metrics()
                  .timer("processImage")
                  .record(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
              exceptions.add(e);
              return;
            }

            // Discard original according to settings
            if (settings.isDiscardOriginal()) {
              log().debug("Discarding image {}", img.getId());
              item.removeContent(img);
            }
          });

      if (exceptions.isEmpty()) return ProcessorResponse.ok();

      return ProcessorResponse.itemError(exceptions);
    }

    private void processImage(Item item, Image img) throws Exception {
      // Based on code from: https://gist.github.com/berak/788da80d1dd5bade3f878210f45d6742
      long start = System.currentTimeMillis();

      Mat frame = OpenCVUtils.bufferedImageToMat(img.getData());

      // Convert to 3-channel RGB
      Imgproc.cvtColor(frame, frame, Imgproc.COLOR_RGBA2RGB);

      // Calculate mean RGB values
      Scalar meanRGB = OpenCVUtils.meanRGB(img.getData());

      // Convert to blob
      Size size = new Size(settings.getSize(), settings.getSize());
      int height = (int) (size.height / 4);
      Mat blob = Dnn.blobFromImage(frame, 1.0, size, meanRGB, true, false);

      long end = System.currentTimeMillis();
      metrics().timer("preprocessing").record(end - start, TimeUnit.MILLISECONDS);
      start = end;

      // Pass blob through to EAST and get outputs
      eastNet.setInput(blob);
      List<Mat> outs = new ArrayList<>(2);
      List<String> outNames = new ArrayList<>();
      outNames.add("feature_fusion/Conv_7/Sigmoid");
      outNames.add("feature_fusion/concat_3");
      eastNet.forward(outs, outNames);

      end = System.currentTimeMillis();
      metrics().timer("east").record(end - start, TimeUnit.MILLISECONDS);
      start = end;

      // Read results from EAST, and decode into RotatedRect
      Mat scores = outs.get(0).reshape(1, height);
      Mat geometry = outs.get(1).reshape(1, 5 * height);
      List<Float> confidencesList = new ArrayList<>();
      List<RotatedRect> boxesList =
          decode(scores, geometry, confidencesList, settings.getScoreThreshold());

      if (boxesList.isEmpty()) {
        log().debug("No text found in image {}", img.getId());
        return;
      }

      // Suppress non-maximal boxes
      MatOfFloat confidences = new MatOfFloat(Converters.vector_float_to_Mat(confidencesList));
      RotatedRect[] boxesArray = boxesList.toArray(new RotatedRect[0]);
      MatOfRotatedRect boxes = new MatOfRotatedRect(boxesArray);
      MatOfInt indices = new MatOfInt();
      Dnn.NMSBoxesRotated(
          boxes, confidences, settings.getScoreThreshold(), settings.getNmsThreshold(), indices);

      // Convert model output into RotatedRect
      List<RotatedRect> rotatedRects =
          Arrays.stream(indices.toArray())
              .mapToObj(i -> boxesArray[i])
              .map(rr -> OpenCVUtils.padRotatedRect(rr, settings.getPadding()))
              .collect(Collectors.toList());

      log().debug("{} text segments found in image {}", rotatedRects.size(), img.getId());

      end = System.currentTimeMillis();
      metrics().timer("decode").record(end - start, TimeUnit.MILLISECONDS);
      start = end;

      // Calculate the scaling ratio we need to apply
      Point ratio =
          new Point((float) frame.cols() / size.width, (float) frame.rows() / size.height);

      switch (settings.getOutputMode()) {
        case BOX:
          // Draw boxes around identified text
          for (RotatedRect rot : rotatedRects) {
            Point[] vertices = OpenCVUtils.scaleRotatedRect(rot, ratio.x, ratio.y);
            for (int j = 0; j < 4; ++j) {
              Imgproc.line(frame, vertices[j], vertices[(j + 1) % 4], new Scalar(0, 0, 255), 1);
            }
          }

          // Save frame to new Image Content
          item.createContent(Image.class)
              .withData(OpenCVUtils.matToBufferedImage(frame))
              .withDescription("EAST output (BOX) from " + img.getId())
              .withProperty(PropertyKeys.PROPERTY_KEY_PARENT, img.getId())
              .save();

          break;
        case EXTRACT:
          // Extract text areas individually into new Image Content

          // TODO: Merge intersecting boxes
          for (RotatedRect rot : rotatedRects) {
            Rect bounding = rot.boundingRect();

            // Reduce image to bounding box
            BufferedImage bounded =
                img.getData()
                    .getSubimage(
                        (int) (bounding.x * ratio.x),
                        (int) (bounding.y * ratio.y),
                        (int) (bounding.width * ratio.x),
                        (int) (bounding.height * ratio.y));

            // Rotate bounding box
            BufferedImage rotated = rotateImageByDegrees(bounded, -rot.angle);

            // Trim bounding box to detections
            int centreX = rotated.getWidth() / 2;
            int centreY = rotated.getHeight() / 2;

            BufferedImage trimmed =
                rotated.getSubimage(
                    (int) ((centreX - (ratio.x * rot.size.width) / 2.0)),
                    (int) ((centreY - (ratio.y * rot.size.height) / 2.0)),
                    (int) (ratio.x * rot.size.width),
                    (int) (ratio.y * rot.size.height));

            // Save trimmed image to new Image Content
            item.createContent(Image.class)
                .withData(trimmed)
                .withDescription("EAST output (EXTRACT) from " + img.getId())
                .withProperty("x", (int) (bounding.x * ratio.x))
                .withProperty("y", (int) (bounding.y * ratio.y))
                .withProperty("width", (int) (bounding.width * ratio.x))
                .withProperty("height", (int) (bounding.height * ratio.y))
                .withProperty("source", img.getId())
                .withProperty("angle", rot.angle)
                .withProperty(PropertyKeys.PROPERTY_KEY_PARENT, img.getId())
                .save();
          }

          break;
        case MASK:
          // Mask out non-text with black pixels

          // Create mask, which is white by default
          Mat mask = new Mat(frame.rows(), frame.cols(), CvType.CV_8U);
          mask.setTo(OpenCVUtils.WHITE);

          // Create masked areas, using black
          for (RotatedRect rot : rotatedRects) {
            Point[] vertices = OpenCVUtils.scaleRotatedRect(rot, ratio.x, ratio.y);

            Imgproc.fillPoly(mask, List.of(new MatOfPoint(vertices)), OpenCVUtils.BLACK);
          }

          // Mask out original image by setting any white pixels in the mask to black,
          // and using the original pixels for black pixels in the mask

          Imgproc.cvtColor(mask, mask, Imgproc.COLOR_GRAY2BGR, 3);
          frame.setTo(OpenCVUtils.BLACK, mask);

          // Save frame to new Image Content
          item.createContent(Image.class)
              .withData(OpenCVUtils.matToBufferedImage(frame))
              .withDescription("EAST output (MASK) from " + img.getId())
              .withProperty(PropertyKeys.PROPERTY_KEY_PARENT, img.getId())
              .save();

          break;
        case INVERSE_MASK:
          // Mask out text with black pixels

          // Create mask, which is black by default
          Mat inverseMask = new Mat(frame.rows(), frame.cols(), CvType.CV_8U);
          inverseMask.setTo(OpenCVUtils.BLACK);

          // Create unmasked areas, using white
          for (RotatedRect rot : rotatedRects) {
            Point[] vertices = OpenCVUtils.scaleRotatedRect(rot, ratio.x, ratio.y);

            Imgproc.fillPoly(inverseMask, List.of(new MatOfPoint(vertices)), OpenCVUtils.WHITE);
          }

          // Mask out original image by setting any white pixels in the mask to black,
          // and using the original pixels for black pixels in the mask

          Imgproc.cvtColor(inverseMask, inverseMask, Imgproc.COLOR_GRAY2BGR, 3);
          frame.setTo(OpenCVUtils.BLACK, inverseMask);

          // Save frame to new Image Content
          item.createContent(Image.class)
              .withData(OpenCVUtils.matToBufferedImage(frame))
              .withDescription("EAST output (INVERSE_MASK) from " + img.getId())
              .withProperty(PropertyKeys.PROPERTY_KEY_PARENT, img.getId())
              .save();

          break;
      }

      end = System.currentTimeMillis();
      metrics().timer("output").record(end - start, TimeUnit.MILLISECONDS);
    }

    private static BufferedImage rotateImageByDegrees(BufferedImage img, double angle) {
      double rads = Math.toRadians(angle);
      double sin = Math.abs(Math.sin(rads)), cos = Math.abs(Math.cos(rads));
      int w = img.getWidth();
      int h = img.getHeight();
      int newWidth = (int) Math.floor(w * cos + h * sin);
      int newHeight = (int) Math.floor(h * cos + w * sin);

      BufferedImage rotated = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g2d = rotated.createGraphics();
      AffineTransform at = new AffineTransform();
      at.translate((newWidth - w) / 2.0, (newHeight - h) / 2.0);

      int x = w / 2;
      int y = h / 2;

      at.rotate(rads, x, y);
      g2d.setTransform(at);
      g2d.drawImage(img, 0, 0, null);
      g2d.dispose();

      return rotated;
    }

    private static List<RotatedRect> decode(
        Mat scores, Mat geometry, List<Float> confidences, float scoreThreshold) {
      int width = geometry.cols();
      int height = geometry.rows() / 5;

      List<RotatedRect> detections = new ArrayList<>();
      for (int y = 0; y < height; ++y) {
        Mat scoresData = scores.row(y);
        Mat x0Data = geometry.submat(0, height, 0, width).row(y);
        Mat x1Data = geometry.submat(height, 2 * height, 0, width).row(y);
        Mat x2Data = geometry.submat(2 * height, 3 * height, 0, width).row(y);
        Mat x3Data = geometry.submat(3 * height, 4 * height, 0, width).row(y);
        Mat anglesData = geometry.submat(4 * height, 5 * height, 0, width).row(y);

        for (int x = 0; x < width; ++x) {
          double score = scoresData.get(0, x)[0];
          if (score >= scoreThreshold) {
            double offsetX = x * 4.0;
            double offsetY = y * 4.0;
            double angle = anglesData.get(0, x)[0];
            double cosA = Math.cos(angle);
            double sinA = Math.sin(angle);
            double x0 = x0Data.get(0, x)[0];
            double x1 = x1Data.get(0, x)[0];
            double x2 = x2Data.get(0, x)[0];
            double x3 = x3Data.get(0, x)[0];
            double h = x0 + x2;
            double w = x1 + x3;
            Point offset =
                new Point(offsetX + cosA * x1 + sinA * x2, offsetY - sinA * x1 + cosA * x2);
            Point p1 = new Point(-1 * sinA * h + offset.x, -1 * cosA * h + offset.y);
            Point p3 = new Point(-1 * cosA * w + offset.x, sinA * w + offset.y);
            RotatedRect r =
                new RotatedRect(
                    new Point(0.5 * (p1.x + p3.x), 0.5 * (p1.y + p3.y)),
                    new Size(w, h),
                    -1 * angle * 180 / Math.PI);
            detections.add(r);
            confidences.add((float) score);
          }
        }
      }
      return detections;
    }
  }

  public static class Settings implements io.annot8.api.settings.Settings {
    private boolean discardOriginal = false;
    private float scoreThreshold = 0.5f;
    private float nmsThreshold = 0.4f;
    private int size = 512;
    private Path eastModel;
    private OutputMode outputMode = OutputMode.MASK;
    private int padding = 0;

    @Override
    public boolean validate() {
      return true;
    }

    @Description("Should the original Content be discarded when an image is extracted?")
    public boolean isDiscardOriginal() {
      return discardOriginal;
    }

    public void setDiscardOriginal(boolean discardOriginal) {
      this.discardOriginal = discardOriginal;
    }

    @Description("Score threshold for the EAST algorithm")
    public float getScoreThreshold() {
      return scoreThreshold;
    }

    public void setScoreThreshold(float scoreThreshold) {
      this.scoreThreshold = scoreThreshold;
    }

    @Description("Non-Maximum Suppression (NMS) threshold for the EAST algorithm")
    public float getNmsThreshold() {
      return nmsThreshold;
    }

    public void setNmsThreshold(float nmsThreshold) {
      this.nmsThreshold = nmsThreshold;
    }

    @Description("Path to the EAST model")
    public Path getEastModel() {
      return eastModel;
    }

    public void setEastModel(Path eastModel) {
      this.eastModel = eastModel;
    }

    @Description("The size in pixels to scale images to for processing")
    public int getSize() {
      return size;
    }

    public void setSize(int size) {
      this.size = size;
    }

    @Description("How the results should be outputted")
    public OutputMode getOutputMode() {
      return outputMode;
    }

    public void setOutputMode(OutputMode outputMode) {
      this.outputMode = outputMode;
    }

    @Description("The amount of padding to add around detections, in scaled units")
    public int getPadding() {
      return padding;
    }

    public void setPadding(int padding) {
      this.padding = padding;
    }
  }

  public enum OutputMode {
    EXTRACT,
    MASK,
    BOX,
    INVERSE_MASK
  }
}
