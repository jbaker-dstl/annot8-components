module io.annot8.components.temporal {
  requires io.annot8.api;
  requires io.annot8.common.components;
  requires io.annot8.common.data;
  requires io.annot8.components.base.text;
  requires io.annot8.conventions;
  requires jakarta.json.bind;
  requires com.google.common;
  requires org.slf4j;

  exports io.annot8.components.temporal.processors;
}
