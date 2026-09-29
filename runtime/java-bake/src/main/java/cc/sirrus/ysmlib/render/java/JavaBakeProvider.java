package cc.sirrus.ysmlib.render.java;

import cc.sirrus.ysmlib.render.*;
import cc.sirrus.ysmlib.render.Geometry.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/** Scalar JVM implementation of the versioned YSM bake semantics. */
public final class JavaBakeProvider implements BakeProvider {
  @Override
  public String profile() {
    return BakedModel.PROFILE;
  }

  @Override
  public byte[] encode(BakedModel model) throws IOException {
    return BakedModelCodec.encode(model);
  }

  @Override
  public BakedModel decode(ByteBuffer payload) throws IOException {
    return BakedModelCodec.decode(payload);
  }

  @Override
  public BakedModel bake(Model source, ByteBuffer rgba, int width, int height, Options options)
      throws IOException {
    require(
        width > 0 && height > 0 && (long) width * height * 4 <= rgba.remaining(),
        "Invalid texture extent or RGBA buffer");
    require(
        options.originVersion() >= 0 && options.originVersion() <= 65535, "Invalid origin version");
    int n = source.bones().size();
    require(n <= 65536, "Too many bones");
    var names = new HashMap<String, Integer>();
    var children = new ArrayList<List<Integer>>(n);
    var roots = new ArrayList<Integer>();
    for (int i = 0; i < n; i++) {
      var bone = source.bones().get(i);
      require(
          bone.name() != null
              && !bone.name().isEmpty()
              && names.putIfAbsent(bone.name(), i) == null,
          "Invalid/duplicate bone name");
      require(bone.pivot().finite() && bone.rotation().finite(), "Non-finite bone metadata");
      children.add(new ArrayList<>());
    }
    for (int i = 0; i < n; i++) {
      var parent = source.bones().get(i).parent();
      if (parent == null || parent.isEmpty()) roots.add(i);
      else {
        require(names.containsKey(parent), "Missing bone parent: " + parent);
        children.get(names.get(parent)).add(i);
      }
    }
    // Explicit entry/exit frames avoid stack overflow for valid deep skeletons.
    record Frame(int original, int parent, int depth, boolean exit, int sorted) {}
    var stack = new ArrayDeque<Frame>();
    for (int i = roots.size() - 1; i >= 0; i--)
      stack.push(new Frame(roots.get(i), -1, 0, false, -1));
    var bones = new ArrayList<BakedModel.Bone>(n);
    boolean[] visited = new boolean[n];
    var texture = rgba.slice();
    while (!stack.isEmpty()) {
      var f = stack.pop();
      if (f.exit()) {
        var b = bones.get(f.sorted());
        bones.set(
            f.sorted(),
            new BakedModel.Bone(
                b.originalIndex(),
                b.parent(),
                bones.size(),
                b.depth(),
                b.pivot(),
                b.solid(),
                b.partitions()));
        continue;
      }
      require(!visited[f.original()], "Cyclic bone hierarchy");
      visited[f.original()] = true;
      var sourceBone = source.bones().get(f.original());
      int sorted = bones.size();
      var partitions = new ArrayList<List<BakedModel.Cube>>(4);
      for (int p = 0; p < 4; p++) partitions.add(new ArrayList<>());
      boolean solid = true;
      for (var cube : sourceBone.cubes())
        solid &= process(cube, texture, width, height, options, partitions);
      bones.add(
          new BakedModel.Bone(
              f.original(), f.parent(), 0, f.depth(), sourceBone.pivot(), solid, partitions));
      stack.push(new Frame(f.original(), f.parent(), f.depth(), true, sorted));
      var descendants = children.get(f.original());
      for (int i = descendants.size() - 1; i >= 0; i--)
        stack.push(new Frame(descendants.get(i), sorted, f.depth() + 1, false, -1));
    }
    require(bones.size() == n, "Cyclic or disconnected hierarchy");
    return new BakedModel(bones, options.hasPbr());
  }

  private static boolean process(
      Geometry.Cube cube,
      ByteBuffer rgba,
      int w,
      int h,
      Options opts,
      List<List<BakedModel.Cube>> parts)
      throws IOException {
    require(cube.positions().size() <= 8 && cube.faces().size() <= 6, "Invalid cube dimensions");
    for (var p : cube.positions()) require(p.finite(), "Non-finite cube position");
    var quads = new ArrayList<BakedModel.Quad>();
    var types = new ArrayList<Integer>();
    V3 center = new V3(0, 0, 0);
    boolean solid = true;
    for (var face : cube.faces()) {
      require(
          face.indices().size() == 4 && face.uv().size() == 4 && face.normal().finite(),
          "Invalid face metadata");
      V3 fc = new V3(0, 0, 0);
      for (int index : face.indices()) {
        require(index >= 0 && index < cube.positions().size(), "Position index out of range");
        fc = fc.add(cube.positions().get(index).mul(.25f));
      }
      float[] uv = new float[8];
      for (int i = 0; i < 4; i++) {
        uv[i * 2] = face.uv().get(i).x();
        uv[i * 2 + 1] = face.uv().get(i).y();
        require(Float.isFinite(uv[i * 2]) && Float.isFinite(uv[i * 2 + 1]), "Non-finite UV");
      }
      int type = inspect(uv, rgba, w, h, opts.originVersion());
      solid &= type == 3;
      var p0 = cube.positions().get(face.indices().get(0));
      var e1 = cube.positions().get(face.indices().get(1)).sub(p0);
      var e2 = cube.positions().get(face.indices().get(2)).sub(p0);
      var tangent = new V4(0, 0, 0, 0);
      if (opts.hasPbr()) {
        float du1 = uv[2] - uv[0], dv1 = uv[3] - uv[1], du2 = uv[4] - uv[0], dv2 = uv[5] - uv[1];
        float den = du1 * dv2 - du2 * dv1, factor = den != 0 ? 1 / den : 1;
        var t = e1.mul(dv2).sub(e2.mul(dv1)).mul(factor).normalized();
        var b = e2.mul(du1).sub(e1.mul(du2)).mul(factor);
        tangent = new V4(t.x(), t.y(), t.z(), Math.signum(b.dot(t.cross(face.normal()))));
      }
      var finalUv = new ArrayList<V2>();
      for (int i = 0; i < 4; i++) finalUv.add(new V2(uv[i * 2], uv[i * 2 + 1]));
      quads.add(
          new BakedModel.Quad(
              face.indices(),
              finalUv,
              face.normal(),
              tangent,
              fc,
              -face.normal().dot(p0),
              Math.signum(face.normal().dot(e1.cross(e2)))));
      types.add(type);
      center = center.add(fc.mul(1f / cube.faces().size()));
    }
    boolean inverted = false;
    if (quads.size() == 6)
      for (var q : quads) {
        var out = q.center().sub(center);
        if (out.dot(out) > 1e-12f && q.normal().dot(out) < 0) {
          inverted = true;
          break;
        }
      }
    var opaque = new ArrayList<BakedModel.Quad>();
    var translucent = new ArrayList<BakedModel.Quad>();
    for (int i = 0; i < quads.size(); i++) {
      int type = types.get(i);
      if (opts.forceTranslucent() || type == 1 || type == 2) translucent.add(quads.get(i));
      else if (type == 3) opaque.add(quads.get(i));
    }
    boolean cull = (opts.forceCulling() && quads.size() == 6) || inverted;
    if (!opaque.isEmpty())
      parts
          .get(cull || opaque.size() == 6 ? 0 : 1)
          .add(
              new BakedModel.Cube(
                  cube.positions(), opaque, Math.min(inverted ? 5 : 3, opaque.size())));
    if (!translucent.isEmpty())
      parts
          .get(cull ? 3 : 2)
          .add(
              new BakedModel.Cube(
                  cube.positions(), translucent, Math.min(inverted ? 5 : 3, translucent.size())));
    return solid;
  }

  /** 0 empty, 1 partial alpha, 2 binary alpha, 3 solid. UV is normalized in place. */
  private static int inspect(float[] uv, ByteBuffer rgba, int w, int h, int version) {
    int mode = version >= 29 ? 29 : version >= 28 ? 28 : version >= 17 ? 17 : version >= 6 ? 6 : 0;
    int minU = w, maxU = 0, minV = h, maxV = 0;
    for (int i = 0; i < 4; i++) {
      int u = quantize(uv[i * 2], w, mode == 0 || mode == 29 ? .6f : .5f);
      int v = quantize(uv[i * 2 + 1], h, mode == 29 ? .4f : mode == 0 ? .6f : .5f);
      if (mode == 0 || mode == 29) {
        uv[i * 2] = (float) u / w;
        uv[i * 2 + 1] = (float) v / h;
      }
      minU = Math.min(minU, u);
      maxU = Math.max(maxU, u);
      minV = Math.min(minV, v);
      maxV = Math.max(maxV, v);
    }
    float uOff = 0, vOff = 0;
    if (mode == 6) {
      if (minU == maxU && maxU < w) maxU++;
      if (minV == maxV && maxV < h) maxV++;
    } else {
      if (minU == maxU) {
        if (maxU < w) maxU++;
        else if (minU > 0) {
          minU--;
          uOff = -1f / w;
        }
      }
      if (minV == maxV) {
        if (mode == 0 && maxV < h) maxV++;
        else if (minV > 0) {
          minV--;
          vOff = -1f / h;
        } else if (maxV < h) maxV++;
      }
    }
    if (mode == 0 || mode == 28 || mode == 29) {
      uv[0] += uOff;
      uv[6] += uOff;
      uv[5] += vOff;
      uv[7] += vOff;
    } else
      for (int i = 0; i < 4; i++) {
        uv[i * 2] += uOff;
        uv[i * 2 + 1] += vOff;
      }
    boolean empty = true, transparent = false;
    for (int y = minV; y < maxV; y++)
      for (int x = minU; x < maxU; x++) {
        int alpha = rgba.get((y * w + x) * 4 + 3) & 255;
        if (alpha != 0) {
          empty = false;
          if (alpha != 255) return 1;
        } else transparent = true;
      }
    return empty ? 0 : transparent ? 2 : 3;
  }

  private static int quantize(float value, int extent, float threshold) {
    float scaled = Math.max(0, Math.min(1, value)) * extent;
    int base = (int) scaled;
    if (base < extent && scaled - base >= threshold) base++;
    return Math.min(base, extent);
  }

  static void require(boolean condition, String message) throws IOException {
    if (!condition) throw new IOException(message);
  }
}
