package com.elfmcys.ysm.natives.render;

import cc.sirrus.ysmlib.render.ModelState;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.nio.ShortBuffer;

/** Minecraft adapter; the state and all transforms are owned by the prerequisite. */
public final class NativeModelState implements com.elfmcys.ysm.util.Closeable {
  private static final int BONE_INFO_INT_COUNT = 8,
      CUBE_INFO_HEADER_INT_COUNT = 2,
      CUBE_INFO_INT_COUNT = 2;
  private final ModelState state = cc.sirrus.ysmlib.YsmRuntime.render().createState();
  private BonePoseView poses;

  private NativeModelState() {}

  public static NativeModelState create() {
    return new NativeModelState();
  }

  public ModelState runtimeState() {
    return state;
  }

  public boolean extract(NativeBakedModel model, float[] attributes) {
    invalidate();
    if (!state.extract(model.runtimeModel(), attributes)) return false;
    poses = new BonePoseView(state);
    return true;
  }

  public void invalidate() {
    state.invalidate();
    poses = null;
  }

  public BonePoseView getBonePoses() {
    return poses;
  }

  public ShortBuffer getRenderBoneIndices() {
    if (!state.valid()) return null;
    var indices = state.renderBones();
    var result = new short[indices.size()];
    for (int i = 0; i < result.length; i++) result[i] = (short) (int) indices.get(i);
    return ShortBuffer.wrap(result).asReadOnlyBuffer();
  }

  public ShortList getLocatorBoneIndices() {
    var result = new ShortArrayList();
    for (int i : state.locators()) result.add((short) i);
    return result;
  }

  public int getTotalVertexCount() {
    return state.valid() ? state.vertices() : 0;
  }

  public int getTranslucentVertexCount() {
    return state.valid() ? state.translucentVertices() : 0;
  }

  public boolean isValid() {
    return state.valid();
  }

  public BoneInfoView calculateRenderBoneInfo() {
    var bones = state.renderBones();
    var data = new int[bones.size() * 8];
    int offset = 0;
    for (int p = 0; p < 4; p++)
      for (int i = 0; i < bones.size(); i++) {
        int count = state.model().bones().get(bones.get(i)).vertices(p);
        data[i * 8 + p * 2] = offset;
        data[i * 8 + p * 2 + 1] = count;
        offset = Math.addExact(offset, count);
      }
    return new BoneInfoView(data, bones.size());
  }

  public CubeInfoView calculateRenderCubeInfo() {
    var bones = state.renderBones();
    int count = 0;
    for (int b : bones)
      for (var part : state.model().bones().get(b).partitions())
        count = Math.addExact(count, part.size());
    int header = bones.size() * 2 + 1;
    var data = new int[Math.addExact(header, Math.multiplyExact(count, 2))];
    data[0] = count;
    var info = calculateRenderBoneInfo();
    int cursor = header;
    for (int b = 0; b < bones.size(); b++)
      for (int p = 0; p < 4; p++) {
        var cubes = state.model().bones().get(bones.get(b)).partitions().get(p);
        if (cubes.size() > 65535)
          throw new IllegalStateException("Cube count exceeds diagnostic view capacity");
        data[1 + b * 2 + p / 2] |= cubes.size() << ((p % 2) * 16);
        int offset = (int) info.get(b, p * 2).vertexOffset();
        for (var c : cubes) {
          int v = (p == 0 || p == 3 ? c.cullingQuads() : c.quads().size()) * 4;
          data[cursor++] = offset;
          data[cursor++] = v;
          offset += v;
        }
      }
    return new CubeInfoView(data, bones.size(), count, header);
  }

  private static void checkIndex(int index, int count, String name) {
    if (index < 0 || index >= count) throw new IndexOutOfBoundsException(name + " index " + index);
  }

  @Override
  public void close() {
    state.close();
    poses = null;
  }

  // 相对于最终 vertex buffer 的 offset；以及根据是否剔除和是否为负尺寸块计算的最大顶点数量。
  public record VertexRange(long vertexOffset, long expectedVertexCount) {
    public static VertexRange unpack(int[] data, int offset) {
      return new VertexRange(
          Integer.toUnsignedLong(data[offset]), Integer.toUnsignedLong(data[offset + 1]));
    }
  }

  public static final class BoneInfoView {
    private final int[] data;
    private final int boneCount;

    private BoneInfoView(int[] data, int boneCount) {
      this.data = data;
      this.boneCount = boneCount;
    }

    public int boneCount() {
      return boneCount;
    }

    public VertexRange getCutout(int boneIndex) {
      return get(boneIndex, 0);
    }

    public VertexRange getCutoutNoCulling(int boneIndex) {
      return get(boneIndex, 2);
    }

    public VertexRange getTranslucent(int boneIndex) {
      return get(boneIndex, 4);
    }

    public VertexRange getTranslucentCulling(int boneIndex) {
      return get(boneIndex, 6);
    }

    private VertexRange get(int boneIndex, int partitionOffset) {
      checkIndex(boneIndex, boneCount, "bone");
      return VertexRange.unpack(data, boneIndex * BONE_INFO_INT_COUNT + partitionOffset);
    }
  }

  public static final class CubeInfoView {
    private final int[] data;
    private final int boneCount;
    private final int cubeCount;
    private final int headerIntCount;

    private CubeInfoView(int[] data, int boneCount, int cubeCount, int headerIntCount) {
      this.data = data;
      this.boneCount = boneCount;
      this.cubeCount = cubeCount;
      this.headerIntCount = headerIntCount;

      var parsedCubeCount = 0;
      for (var boneIndex = 0; boneIndex < boneCount; ++boneIndex) {
        parsedCubeCount = Math.addExact(parsedCubeCount, getBoneCubeCountUnchecked(boneIndex));
      }
      if (parsedCubeCount != cubeCount) {
        throw new IllegalStateException("Invalid native cube info");
      }
    }

    public int boneCount() {
      return boneCount;
    }

    public int cubeCount() {
      return cubeCount;
    }

    public int getCutoutCubeCount(int boneIndex) {
      return getPartitionCubeCount(boneIndex, 0);
    }

    public int getCutoutNoCullingCubeCount(int boneIndex) {
      return getPartitionCubeCount(boneIndex, 1);
    }

    public int getTranslucentCubeCount(int boneIndex) {
      return getPartitionCubeCount(boneIndex, 2);
    }

    public int getTranslucentCullingCubeCount(int boneIndex) {
      return getPartitionCubeCount(boneIndex, 3);
    }

    public VertexRange getCutout(int boneIndex, int cubeIndex) {
      return get(boneIndex, 0, cubeIndex);
    }

    public VertexRange getCutoutNoCulling(int boneIndex, int cubeIndex) {
      return get(boneIndex, 1, cubeIndex);
    }

    public VertexRange getTranslucent(int boneIndex, int cubeIndex) {
      return get(boneIndex, 2, cubeIndex);
    }

    public VertexRange getTranslucentCulling(int boneIndex, int cubeIndex) {
      return get(boneIndex, 3, cubeIndex);
    }

    private VertexRange get(int boneIndex, int partition, int cubeIndex) {
      checkIndex(boneIndex, boneCount, "bone");
      var partitionCubeCount = getPartitionCubeCountInternal(boneIndex, partition);
      checkIndex(cubeIndex, partitionCubeCount, "cube");

      var packedCubeIndex = 0;
      for (var index = 0; index < boneIndex; ++index) {
        packedCubeIndex = Math.addExact(packedCubeIndex, getBoneCubeCountUnchecked(index));
      }
      for (var index = 0; index < partition; ++index) {
        packedCubeIndex =
            Math.addExact(packedCubeIndex, getPartitionCubeCountInternal(boneIndex, index));
      }
      packedCubeIndex = Math.addExact(packedCubeIndex, cubeIndex);
      return VertexRange.unpack(data, headerIntCount + packedCubeIndex * CUBE_INFO_INT_COUNT);
    }

    private int getPartitionCubeCount(int boneIndex, int partition) {
      checkIndex(boneIndex, boneCount, "bone");
      return getPartitionCubeCountInternal(boneIndex, partition);
    }

    private int getPartitionCubeCountInternal(int boneIndex, int partition) {
      var headerOffset = boneIndex * CUBE_INFO_HEADER_INT_COUNT + 1;
      var packedCount = data[headerOffset + partition / 2];
      return partition % 2 == 0 ? packedCount & 0xffff : packedCount >>> 16;
    }

    private int getBoneCubeCountUnchecked(int boneIndex) {
      return Math.addExact(
          Math.addExact(
              getPartitionCubeCountInternal(boneIndex, 0),
              getPartitionCubeCountInternal(boneIndex, 1)),
          Math.addExact(
              getPartitionCubeCountInternal(boneIndex, 2),
              getPartitionCubeCountInternal(boneIndex, 3)));
    }
  }
}
