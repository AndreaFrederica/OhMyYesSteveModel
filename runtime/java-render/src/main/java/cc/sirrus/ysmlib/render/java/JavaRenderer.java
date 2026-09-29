package cc.sirrus.ysmlib.render.java;

import cc.sirrus.ysmlib.render.Geometry;
import cc.sirrus.ysmlib.render.ModelState;
import cc.sirrus.ysmlib.render.Renderer;
import java.nio.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** CPU rendering with transactional output and no dependency on a game or graphics driver. */
public final class JavaRenderer implements Renderer {
  private record Quad(Vertex[] vertices, float depth) {}

  private static final Vertex ZERO = new Vertex(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

  public List<Vertex> render(ModelState state, Parameters params) {
    if (!params.model().isFinite()
        || !params.normal().isFinite()
        || !params.view().isFinite()
        || !params.projection().isFinite())
      throw new IllegalArgumentException("Non-finite draw matrix");
    var opaque = new ArrayList<Quad>();
    var translucent = new ArrayList<Quad>();
    var clipOuter = new Matrix4f(params.projection()).mul(params.view()).mul(params.model());
    boolean outerUniform = uniform(params.model());
    for (int partition = 0; partition < 4; partition++)
      for (int boneIndex : state.renderBones()) {
        var bone = state.model().bones().get(boneIndex);
        var pose = new Matrix4f(params.model()).mul(state.pose(boneIndex, new Matrix4f()));
        var normal = new Matrix3f(params.normal()).mul(state.normal(boneIndex, new Matrix3f()));
        var clip = new Matrix4f(clipOuter).mul(state.pose(boneIndex, new Matrix4f()));
        if (!pose.isFinite() || !normal.isFinite() || !clip.isFinite())
          throw new IllegalArgumentException("Non-finite bone draw matrix");
        float[] facing = facing(clip);
        boolean isUniform = outerUniform && state.uniform(boneIndex),
            cull = partition == 0 || partition == 3;
        float orientation = params.model().determinant3x3() < 0 ? -1 : 1;
        orientation *= state.orientation(boneIndex);
        int color = combine(params.rgba(), state.color(boneIndex), partition >= 2);
        int glow = state.glow(boneIndex),
            light = glow == 255 ? params.light() : (glow << 4) | (glow << 20);
        var output = partition < 2 ? opaque : translucent;
        for (var cube : bone.partitions().get(partition)) {
          int written = 0, capacity = cull ? cube.cullingQuads() : cube.quads().size();
          for (var q : cube.quads()) {
            float f =
                (q.normal().x() * facing[0]
                        + q.normal().y() * facing[1]
                        + q.normal().z() * facing[2]
                        + q.planeD() * facing[3])
                    * q.windingSign();
            boolean back = f <= 0;
            if (cull && back) continue;
            var n = normal.transform(vec(q.normal()));
            if (!isUniform) normalize(n);
            if (back) n.negate();
            int packedNormal = pack(n.x, n.y, n.z, 0);
            int tangent = 0;
            if (state.model().hasPbr() && !params.shadow()) {
              var t = q.tangent();
              var tv = new Vector3f(t.x(), t.y(), t.z());
              if (isUniform) normal.transform(tv);
              else {
                pose.transformDirection(tv);
                normalize(tv);
              }
              float handed = t.w() == 0 ? 1 : t.w() * orientation * (back ? -1 : 1);
              tangent = pack(tv.x, tv.y, tv.z, handed);
            }
            float midU = 0, midV = 0;
            for (var uv : q.uv()) {
              midU += uv.x() * .25f;
              midV += uv.y() * .25f;
            }
            var vertices = new Vertex[4];
            for (int v = 0; v < 4; v++) {
              var point = pose.transformPosition(vec(cube.positions().get(q.indices().get(v))));
              var uv = q.uv().get(v);
              if (!point.isFinite())
                throw new IllegalArgumentException("Non-finite transformed position");
              vertices[v] =
                  new Vertex(
                      point.x,
                      point.y,
                      point.z,
                      color,
                      uv.x(),
                      uv.y(),
                      params.overlay(),
                      light,
                      packedNormal,
                      midU,
                      midV,
                      tangent,
                      params.entity());
            }
            var center =
                clip.transform(new Vector4f(q.center().x(), q.center().y(), q.center().z(), 1));
            output.add(new Quad(vertices, center.z / center.w));
            if (++written == capacity) break;
          }
          while (written++ < capacity)
            output.add(new Quad(new Vertex[] {ZERO, ZERO, ZERO, ZERO}, Float.NaN));
        }
      }
    if (!params.shadow())
      translucent.sort(
          (a, b) ->
              Float.compare(
                  Float.isFinite(b.depth) ? b.depth : Float.NEGATIVE_INFINITY,
                  Float.isFinite(a.depth) ? a.depth : Float.NEGATIVE_INFINITY));
    var result = new ArrayList<Vertex>(state.vertices());
    for (var q : opaque) Collections.addAll(result, q.vertices);
    for (var q : translucent) Collections.addAll(result, q.vertices);
    if (result.size() != state.vertices())
      throw new IllegalStateException("Render capacity mismatch");
    return List.copyOf(result);
  }

  public void write(List<Vertex> vertices, Layout layout, ByteBuffer output) {
    if ((long) vertices.size() * layout.stride > output.remaining())
      throw new IllegalArgumentException("Vertex output too small");
    var dst = output.slice().order(ByteOrder.LITTLE_ENDIAN);
    int base = 0;
    for (var v : vertices) {
      for (int b = 0; b < layout.stride; b++) dst.put(base + b, (byte) 0);
      dst.putFloat(base, v.x())
          .putFloat(base + 4, v.y())
          .putFloat(base + 8, v.z())
          .putInt(base + 12, v.rgba());
      dst.putFloat(base + 16, v.u())
          .putFloat(base + 20, v.v())
          .putInt(base + 24, v.overlay())
          .putInt(base + 28, v.light())
          .putInt(base + 32, v.normal());
      if (layout != Layout.VANILLA) {
        dst.putShort(base + 36, (short) v.entity())
            .putShort(base + 38, (short) (v.entity() >>> 16))
            .putShort(base + 40, (short) (v.entity() >>> 32));
        dst.putFloat(base + layout.midOffset, v.midU())
            .putFloat(base + layout.midOffset + 4, v.midV())
            .putInt(base + layout.midOffset + 8, v.tangent());
      }
      base += layout.stride;
    }
  }

  private static int combine(int parent, int bone, boolean transparent) {
    int result = 0;
    for (int i = 0; i < 4; i++) {
      int a = (parent >>> (i * 8)) & 255, b = (bone >>> (i * 8)) & 255;
      int value = i == 3 && !transparent ? a : (a * b + 127) / 255;
      result |= value << (i * 8);
    }
    return result;
  }

  private static Vector3f vec(Geometry.V3 v) {
    return new Vector3f(v.x(), v.y(), v.z());
  }

  private static void normalize(Vector3f v) {
    float n = v.lengthSquared();
    if (n > 0 && Float.isFinite(n)) v.mul(1 / (float) Math.sqrt(n));
    else v.zero();
  }

  private static int pack(float x, float y, float z, float w) {
    return snorm(x) | (snorm(y) << 8) | (snorm(z) << 16) | (snorm(w) << 24);
  }

  private static int snorm(float value) {
    return ((int) Math.max(-127, Math.min(127, Math.rint(value * 127)))) & 255;
  }

  private static boolean uniform(Matrix4fc m) {
    var a = new Vector3f(m.m00(), m.m01(), m.m02());
    var b = new Vector3f(m.m10(), m.m11(), m.m12());
    var c = new Vector3f(m.m20(), m.m21(), m.m22());
    float x = a.lengthSquared(),
        y = b.lengthSquared(),
        z = c.lengthSquared(),
        max = Math.max(x, Math.max(y, z)),
        epsilon = max * 32 * Math.ulp(1f);
    return max > 0
        && Float.isFinite(max)
        && Math.abs(x - y) <= epsilon
        && Math.abs(x - z) <= epsilon
        && Math.abs(a.dot(b)) <= epsilon
        && Math.abs(a.dot(c)) <= epsilon
        && Math.abs(b.dot(c)) <= epsilon;
  }

  private static float[] facing(Matrix4fc m) {
    float[] x = {m.m00(), m.m10(), m.m20(), m.m30()},
        y = {m.m01(), m.m11(), m.m21(), m.m31()},
        w = {m.m03(), m.m13(), m.m23(), m.m33()};
    return new float[] {
      det(x, y, w, 1, 2, 3), -det(x, y, w, 0, 2, 3), det(x, y, w, 0, 1, 3), -det(x, y, w, 0, 1, 2)
    };
  }

  private static float det(float[] a, float[] b, float[] c, int x, int y, int z) {
    return a[x] * (b[y] * c[z] - b[z] * c[y])
        - a[y] * (b[x] * c[z] - b[z] * c[x])
        + a[z] * (b[x] * c[y] - b[y] * c[x]);
  }
}
