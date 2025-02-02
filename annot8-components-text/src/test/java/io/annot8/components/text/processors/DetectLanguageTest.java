/* Annot8 (annot8.io) - Licensed under Apache-2.0. */
package io.annot8.components.text.processors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.annot8.api.annotations.Annotation;
import io.annot8.api.components.Processor;
import io.annot8.api.data.Item;
import io.annot8.api.exceptions.Annot8Exception;
import io.annot8.api.stores.AnnotationStore;
import io.annot8.common.data.bounds.ContentBounds;
import io.annot8.common.data.content.Text;
import io.annot8.conventions.AnnotationTypes;
import io.annot8.conventions.PropertyKeys;
import io.annot8.testing.testimpl.TestItem;
import io.annot8.testing.testimpl.content.TestStringContent;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class DetectLanguageTest {

  @Test
  public void testDetectLanguageEnglish() throws Annot8Exception {
    // Taken from Pride and Prejudice
    doTest(
        "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.\n"
            + "However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so "
            + "well fixed in the minds of the surrounding families, that he is considered the rightful property of some one or other of their daughters.",
        "en");
  }

  @Test
  public void testDetectLanguageGerman() throws Annot8Exception {
    // Taken from Der Mord an der Jungfrau
    doTest(
        "Immerzu traurig, Amaryllis! sollten dich die jungen Herrn im Stich\n"
            + "gelassen haben, deine Blüten welk, deine Wohlgerüche ausgehaucht sein? Ließ\n"
            + "Atys, das göttliche Kind, von dir mit seinen eitlen Liebkosungen?",
        "de");
  }

  private void doTest(String sourceText, String expectedLanguage) throws Annot8Exception {
    try (Processor p = new DetectLanguage.Processor()) {
      Item item = new TestItem();

      Text content = item.createContent(TestStringContent.class).withData(sourceText).save();

      p.process(item);

      AnnotationStore store = content.getAnnotations();

      List<Annotation> annotations = store.getAll().collect(Collectors.toList());
      assertEquals(1, annotations.size());

      Annotation a = annotations.get(0);
      assertEquals(ContentBounds.getInstance(), a.getBounds());
      assertEquals(AnnotationTypes.ANNOTATION_TYPE_LANGUAGE, a.getType());

      assertEquals(2, a.getProperties().getAll().size());
      Optional<Object> o1 = a.getProperties().get(PropertyKeys.PROPERTY_KEY_LANGUAGE);
      assertTrue(o1.isPresent());
      assertEquals(expectedLanguage, o1.get());

      Optional<Object> o2 = a.getProperties().get(PropertyKeys.PROPERTY_KEY_PROBABILITY);
      assertTrue(o2.isPresent());
      assertTrue((Double) o2.get() > 0.5);
    }
  }
}
