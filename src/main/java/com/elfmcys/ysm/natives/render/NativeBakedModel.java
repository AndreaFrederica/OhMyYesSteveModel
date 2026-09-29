package com.elfmcys.ysm.natives.render;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.render.BakedModel;
import cc.sirrus.ysmlib.render.Geometry;
import com.elfmcys.ysm.buffer.*;
import com.elfmcys.ysm.mixin.client.NativeImageAccessor;
import com.elfmcys.ysm.model.resource.client.render.RuntimeGeometryMapper;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModel;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import us.hebi.quickbuf.ProtoSource;

/** Host adapter for the prerequisite's JVM bake service. Owns no JNI object. */
public final class NativeBakedModel implements com.elfmcys.ysm.util.Closeable {
  public record Info(int boneCount, boolean guiNoShadow, boolean hasPbr) {}

  public record BonePartitionInfo(int cubeCount, int vertexCount, int vertexCountAfterCulling) {}

  public record BoneInfo(
      BonePartitionInfo cutout,
      BonePartitionInfo cutoutNoCulling,
      BonePartitionInfo translucent,
      BonePartitionInfo translucentCulling) {}

  public record QuadData(
      Vector3f normal,
      Vector4f tangent,
      Vector2f[] uv,
      int vertex0,
      int vertex1,
      int vertex2,
      int vertex3,
      float planeD,
      float windingSign) {}

  public record CubeData(
      Vector3f[] positions, QuadData[] quads, int quadCount, int quadCountAfterCulling) {}

  public record BakeResult(NativeBuffer bakedData, short[] sortedBoneIndices) {}

  public record ReadResult(NativeBakedModel bakedModel, short[] sortedBoneIndices) {}

  private BakedModel model;

  private NativeBakedModel(BakedModel model) {
    this.model = model;
  }

  public BakedModel runtimeModel() {
    if (model == null) throw new IllegalStateException("Baked model is closed");
    return model;
  }

  public static int capability() {
    return 0;
  }

  public Info getInfo() {
    var m = runtimeModel();
    return new Info(m.bones().size(), false, m.hasPbr());
  }

  public BoneInfo[] getBoneInfo(int start, int count) {
    var m = runtimeModel();
    java.util.Objects.checkFromIndexSize(start, count, m.bones().size());
    var result = new BoneInfo[count];
    for (int i = 0; i < count; i++) {
      var b = m.bones().get(start + i);
      var parts = new BonePartitionInfo[4];
      for (int p = 0; p < 4; p++) {
        var cubes = b.partitions().get(p);
        parts[p] =
            new BonePartitionInfo(
                cubes.size(),
                cubes.stream().mapToInt(c -> c.quads().size() * 4).sum(),
                cubes.stream().mapToInt(c -> c.cullingQuads() * 4).sum());
      }
      result[i] = new BoneInfo(parts[0], parts[1], parts[2], parts[3]);
    }
    return result;
  }

  public CubeData[] getCubeData(int bone, int partition, int start, int count) {
    var cubes = runtimeModel().bones().get(bone).partitions().get(partition);
    java.util.Objects.checkFromIndexSize(start, count, cubes.size());
    var result = new CubeData[count];
    for (int i = 0; i < count; i++) {
      var c = cubes.get(start + i);
      var pos = new Vector3f[8];
      for (int v = 0; v < 8; v++)
        pos[v] = v < c.positions().size() ? vec(c.positions().get(v)) : new Vector3f();
      var quads = new QuadData[c.quads().size()];
      for (int q = 0; q < quads.length; q++) {
        var f = c.quads().get(q);
        var t = f.tangent();
        quads[q] =
            new QuadData(
                vec(f.normal()),
                new Vector4f(t.x(), t.y(), t.z(), t.w()),
                f.uv().stream().map(u -> new Vector2f(u.x(), u.y())).toArray(Vector2f[]::new),
                f.indices().get(0),
                f.indices().get(1),
                f.indices().get(2),
                f.indices().get(3),
                f.planeD(),
                f.windingSign());
      }
      result[i] = new CubeData(pos, quads, quads.length, c.cullingQuads());
    }
    return result;
  }

  private static Vector3f vec(Geometry.V3 v) {
    return new Vector3f(v.x(), v.y(), v.z());
  }

  public static BakeResult bake(
      GeoModel source,
      NativeImage texture,
      int version,
      boolean cull,
      boolean translucent,
      boolean pbr)
      throws IOException {
    if (texture.format() != NativeImage.Format.RGBA)
      throw new IllegalArgumentException("Texture must be RGBA");
    var access = (NativeImageAccessor) (Object) texture;
    try {
      return bake(
          source,
          MemoryUtil.memByteBuffer(
              access.ysm$pixels(),
              Math.multiplyExact(Math.multiplyExact(texture.getWidth(), texture.getHeight()), 4)),
          texture.getWidth(),
          texture.getHeight(),
          version,
          cull,
          translucent,
          pbr);
    } finally {
      Reference.reachabilityFence(texture);
    }
  }

  public static BakeResult bake(
      UniBuffer source,
      int bones,
      NativeImage texture,
      int version,
      boolean cull,
      boolean translucent,
      boolean pbr) {
    try {
      var geo = parse(source, bones);
      return bake(geo, texture, version, cull, translucent, pbr);
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid geometry", e);
    }
  }

  public static BakeResult bake(
      UniBuffer source,
      int bones,
      NativeBuffer rgba,
      int width,
      int height,
      int version,
      boolean pbr) {
    return bake(source, bones, rgba, width, height, version, false, false, pbr);
  }

  public static BakeResult bake(
      UniBuffer source,
      int bones,
      NativeBuffer rgba,
      int width,
      int height,
      int version,
      boolean cull,
      boolean translucent,
      boolean pbr) {
    try {
      return bake(parse(source, bones), rgba.nio(), width, height, version, cull, translucent, pbr);
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid geometry", e);
    } finally {
      Reference.reachabilityFence(rgba);
      Reference.reachabilityFence(source);
    }
  }

  private static GeoModel parse(UniBuffer source, int bones) throws IOException {
    var model = GeoModel.parseFrom(ProtoSource.newInstance(source.nio()));
    if (model.bones().size() != bones) throw new IOException("Bone count mismatch");
    return model;
  }

  private static BakeResult bake(
      GeoModel source,
      ByteBuffer rgba,
      int width,
      int height,
      int version,
      boolean cull,
      boolean translucent,
      boolean pbr)
      throws IOException {
    var result =
        YsmRuntime.bake()
            .bake(
                RuntimeGeometryMapper.map(source),
                rgba,
                width,
                height,
                new Geometry.Options(version, cull, translucent, pbr));
    return new BakeResult(
        NativeBuffer.copyOf(ByteBuffer.wrap(YsmRuntime.bake().encode(result))),
        result.sortedIndices());
  }

  public static ReadResult read(UniBuffer payload, int bones) {
    try {
      var model = YsmRuntime.bake().decode(payload.nio());
      if (model.bones().size() != bones) throw new IOException("Bone count mismatch");
      return new ReadResult(new NativeBakedModel(model), model.sortedIndices());
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid baked cache", e);
    } finally {
      Reference.reachabilityFence(payload);
    }
  }

  public static boolean tryBake(
      GeoModel source,
      NativeBuffer rgba,
      int w,
      int h,
      int version,
      boolean cull,
      boolean translucent,
      boolean pbr)
      throws IOException {
    try {
      YsmRuntime.bake()
          .bake(
              RuntimeGeometryMapper.map(source),
              rgba.nio(),
              w,
              h,
              new Geometry.Options(version, cull, translucent, pbr));
      return true;
    } catch (IOException | IllegalArgumentException e) {
      return false;
    } finally {
      Reference.reachabilityFence(rgba);
    }
  }

  public static boolean tryBake(
      UniBuffer source,
      NativeBuffer rgba,
      int w,
      int h,
      int version,
      boolean cull,
      boolean translucent,
      boolean pbr) {
    try {
      return tryBake(
          GeoModel.parseFrom(ProtoSource.newInstance(source.nio())),
          rgba,
          w,
          h,
          version,
          cull,
          translucent,
          pbr);
    } catch (IOException | IllegalArgumentException e) {
      return false;
    }
  }

  public static boolean tryBake(
      UniBuffer source,
      NativeImage image,
      int version,
      boolean cull,
      boolean translucent,
      boolean pbr) {
    try {
      var result =
          bake(
              GeoModel.parseFrom(ProtoSource.newInstance(source.nio())),
              image,
              version,
              cull,
              translucent,
              pbr);
      result.bakedData().close();
      return true;
    } catch (IOException | IllegalArgumentException e) {
      return false;
    }
  }

  @Override
  public void close() {
    model = null;
  }
}
