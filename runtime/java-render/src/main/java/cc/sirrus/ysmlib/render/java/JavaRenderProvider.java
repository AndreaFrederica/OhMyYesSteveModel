package cc.sirrus.ysmlib.render.java;

import cc.sirrus.ysmlib.render.*;

public final class JavaRenderProvider implements RenderProvider {
  private final Renderer renderer = new JavaRenderer();

  @Override
  public String id() {
    return "java-render";
  }

  @Override
  public ModelState createState() {
    return new JavaModelState();
  }

  @Override
  public Renderer renderer() {
    return renderer;
  }
}
