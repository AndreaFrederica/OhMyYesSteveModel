package cc.sirrus.ysmlib.render;

import java.util.List;

/** Host-neutral, immutable geometry. Positions are in render units; pivots in model units. */
public final class Geometry {
  private Geometry() {}

  public record V2(float x, float y) {}

  public record V3(float x, float y, float z) {
    public V3 add(V3 b) {
      return new V3(x + b.x, y + b.y, z + b.z);
    }

    public V3 sub(V3 b) {
      return new V3(x - b.x, y - b.y, z - b.z);
    }

    public V3 mul(float s) {
      return new V3(x * s, y * s, z * s);
    }

    public float dot(V3 b) {
      return x * b.x + y * b.y + z * b.z;
    }

    public V3 cross(V3 b) {
      return new V3(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x);
    }

    public V3 normalized() {
      float n = dot(this);
      return n == 0 ? this : mul(1 / (float) Math.sqrt(n));
    }

    public boolean finite() {
      return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
    }
  }

  public record V4(float x, float y, float z, float w) {}

  public record Face(List<Integer> indices, List<V2> uv, V3 normal) {
    public Face {
      indices = List.copyOf(indices);
      uv = List.copyOf(uv);
    }
  }

  public record Cube(List<V3> positions, List<Face> faces) {
    public Cube {
      positions = List.copyOf(positions);
      faces = List.copyOf(faces);
    }
  }

  public record Bone(String name, String parent, V3 pivot, V3 rotation, List<Cube> cubes) {
    public Bone {
      cubes = List.copyOf(cubes);
    }
  }

  public record Model(List<Bone> bones) {
    public Model {
      bones = List.copyOf(bones);
    }
  }

  public record Options(
      int originVersion, boolean forceCulling, boolean forceTranslucent, boolean hasPbr) {}
}
