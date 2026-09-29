package cc.sirrus.ysmlib.render;

/** Replaceable state and rendering capability; the JVM provider is always installed. */
public interface RenderProvider {
  String id();

  /** State extraction and vertex rendering may use different backends. */
  default String stateId() { return id(); }

  ModelState createState();

  Renderer renderer();
}
