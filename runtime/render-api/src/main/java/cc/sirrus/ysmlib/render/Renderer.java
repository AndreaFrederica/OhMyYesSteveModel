package cc.sirrus.ysmlib.render;

import java.nio.ByteBuffer;
import java.util.List;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

/** Portable draw contract. Providers must finish validation before returning vertices. */
public interface Renderer {
  public record Vertex(
      float x,
      float y,
      float z,
      int rgba,
      float u,
      float v,
      int overlay,
      int light,
      int normal,
      float midU,
      float midV,
      int tangent,
      long entity) {}

  public record Parameters(
      Matrix4fc model,
      Matrix3fc normal,
      Matrix4fc view,
      Matrix4fc projection,
      int rgba,
      int light,
      int overlay,
      long entity,
      boolean shadow) {}

  public enum Layout {
    VANILLA(36, 0),
    IRIS_54(54, 42),
    IRIS_55(55, 42),
    IRIS_56(56, 42),
    IRIS_56_AR(56, 44);
    public final int stride, midOffset;

    Layout(int stride, int midOffset) {
      this.stride = stride;
      this.midOffset = midOffset;
    }
  }

  List<Vertex> render(ModelState state, Parameters parameters);

  void write(List<Vertex> vertices, Layout layout, ByteBuffer destination);

  /** Whole-draw entry point. Implementations may avoid allocating per-vertex Java objects.
   * The destination position is unchanged, and failed validation must not publish partial output. */
  default void renderInto(ModelState state, Parameters parameters, Layout layout,
                          ByteBuffer destination) {
    write(render(state, parameters), layout, destination);
  }
}
