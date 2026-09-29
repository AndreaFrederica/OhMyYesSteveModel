package cc.sirrus.ysmlib.render;

import cc.sirrus.ysmlib.render.Geometry.*;
import java.util.List;

/** Immutable logical representation; no native pointers or SIMD-dependent layout. */
public record BakedModel(List<Bone> bones, boolean hasPbr) {
  public static final String PROFILE = "ysmlib-java-bake-1";

  public BakedModel {
    bones = List.copyOf(bones);
  }

  public record Quad(
      List<Integer> indices,
      List<V2> uv,
      V3 normal,
      V4 tangent,
      V3 center,
      float planeD,
      float windingSign) {
    public Quad {
      indices = List.copyOf(indices);
      uv = List.copyOf(uv);
    }
  }

  public record Cube(List<V3> positions, List<Quad> quads, int cullingQuads) {
    public Cube {
      positions = List.copyOf(positions);
      quads = List.copyOf(quads);
    }
  }

  /**
   * Partition order: opaque cull, opaque double sided, translucent double sided, translucent cull.
   */
  public record Bone(
      int originalIndex,
      int parent,
      int subtreeEnd,
      int depth,
      V3 pivot,
      boolean solid,
      List<List<Cube>> partitions) {
    public Bone {
      partitions = partitions.stream().map(List::copyOf).toList();
    }

    public int vertices(int partition) {
      return partitions.get(partition).stream()
          .mapToInt(
              c -> (partition == 0 || partition == 3 ? c.cullingQuads() : c.quads().size()) * 4)
          .sum();
    }
  }

  public short[] sortedIndices() {
    short[] result = new short[bones.size()];
    for (int i = 0; i < result.length; i++) result[i] = (short) bones.get(i).originalIndex();
    return result;
  }
}
