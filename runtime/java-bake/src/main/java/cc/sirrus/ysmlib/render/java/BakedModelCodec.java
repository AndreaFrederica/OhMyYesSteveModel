package cc.sirrus.ysmlib.render.java;

import static cc.sirrus.ysmlib.render.java.JavaBakeProvider.require;

import cc.sirrus.ysmlib.render.BakedModel;
import cc.sirrus.ysmlib.render.Geometry.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Private, platform-independent cache format. Strict lengths; never Java object serialization. */
final class BakedModelCodec {
  private static final int MAGIC = 0x59424a31;

  static byte[] encode(BakedModel model) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeInt(MAGIC);
    out.writeBoolean(model.hasPbr());
    out.writeInt(model.bones().size());
    for (var b : model.bones()) {
      out.writeInt(b.originalIndex());
      out.writeInt(b.parent());
      out.writeInt(b.subtreeEnd());
      out.writeInt(b.depth());
      vector(out, b.pivot());
      out.writeBoolean(b.solid());
      for (var partition : b.partitions()) {
        out.writeInt(partition.size());
        for (var cube : partition) {
          out.writeInt(cube.positions().size());
          for (var p : cube.positions()) vector(out, p);
          out.writeInt(cube.quads().size());
          out.writeInt(cube.cullingQuads());
          for (var q : cube.quads()) {
            for (int i : q.indices()) out.writeInt(i);
            for (var uv : q.uv()) {
              out.writeFloat(uv.x());
              out.writeFloat(uv.y());
            }
            vector(out, q.normal());
            out.writeFloat(q.tangent().x());
            out.writeFloat(q.tangent().y());
            out.writeFloat(q.tangent().z());
            out.writeFloat(q.tangent().w());
            vector(out, q.center());
            out.writeFloat(q.planeD());
            out.writeFloat(q.windingSign());
          }
        }
      }
    }
    return bytes.toByteArray();
  }

  static BakedModel decode(ByteBuffer input) throws IOException {
    var view = input.slice();
    var in =
        new DataInputStream(
            new InputStream() {
              @Override
              public int read() {
                return view.hasRemaining() ? view.get() & 255 : -1;
              }

              @Override
              public int read(byte[] b, int off, int len) {
                if (len == 0) return 0;
                if (!view.hasRemaining()) return -1;
                int n = Math.min(len, view.remaining());
                view.get(b, off, n);
                return n;
              }

              @Override
              public int available() {
                return view.remaining();
              }
            });
    require(in.readInt() == MAGIC, "Incompatible baked profile");
    boolean pbr = in.readBoolean();
    int count = count(in, 65536, 45);
    var bones = new ArrayList<BakedModel.Bone>(count);
    boolean[] originals = new boolean[count];
    for (int i = 0; i < count; i++) {
      int original = in.readInt(), parent = in.readInt(), end = in.readInt(), depth = in.readInt();
      require(
          original >= 0 && original < count && !originals[original], "Invalid bone permutation");
      originals[original] = true;
      require(parent >= -1 && parent < i && end > i && end <= count, "Invalid hierarchy range");
      require(
          depth == (parent == -1 ? 0 : bones.get(parent).depth() + 1), "Invalid hierarchy depth");
      // The nearest open ancestor is the only valid parent in preorder.
      int expectedParent = i - 1;
      while (expectedParent >= 0 && bones.get(expectedParent).subtreeEnd() <= i)
        expectedParent = bones.get(expectedParent).parent();
      require(
          parent == expectedParent && (parent < 0 || end <= bones.get(parent).subtreeEnd()),
          "Invalid preorder hierarchy");
      var pivot = vector(in);
      boolean solid = in.readBoolean();
      var parts = new ArrayList<List<BakedModel.Cube>>(4);
      for (int p = 0; p < 4; p++) {
        int cubes = count(in, 1_000_000, 12);
        var list = new ArrayList<BakedModel.Cube>();
        for (int c = 0; c < cubes; c++) {
          int positions = count(in, 8, 12);
          var pos = new ArrayList<V3>();
          for (int x = 0; x < positions; x++) pos.add(vector(in));
          int quads = count(in, 6, 88), culled = in.readInt();
          require(quads > 0 && culled > 0 && culled <= quads, "Invalid quad capacity");
          var faces = new ArrayList<BakedModel.Quad>();
          for (int f = 0; f < quads; f++) {
            var indices = new ArrayList<Integer>();
            for (int v = 0; v < 4; v++) {
              int ix = in.readInt();
              require(ix >= 0 && ix < positions, "Invalid vertex index");
              indices.add(ix);
            }
            var uv = new ArrayList<V2>();
            for (int v = 0; v < 4; v++) uv.add(new V2(finite(in), finite(in)));
            var normal = vector(in);
            var tangent = new V4(finite(in), finite(in), finite(in), finite(in));
            var center = vector(in);
            float d = finite(in), winding = finite(in);
            require(winding == -1 || winding == 0 || winding == 1, "Invalid winding");
            faces.add(new BakedModel.Quad(indices, uv, normal, tangent, center, d, winding));
          }
          list.add(new BakedModel.Cube(pos, faces, culled));
        }
        parts.add(list);
      }
      bones.add(new BakedModel.Bone(original, parent, end, depth, pivot, solid, parts));
    }
    require(in.available() == 0, "Trailing baked payload");
    return new BakedModel(bones, pbr);
  }

  private static int count(DataInputStream in, int max, int minBytes) throws IOException {
    int n = in.readInt();
    require(
        n >= 0 && n <= max && (long) n * minBytes <= in.available(),
        "Invalid baked payload length");
    return n;
  }

  private static float finite(DataInputStream in) throws IOException {
    float v = in.readFloat();
    require(Float.isFinite(v), "Non-finite baked value");
    return v;
  }

  private static V3 vector(DataInputStream in) throws IOException {
    return new V3(finite(in), finite(in), finite(in));
  }

  private static void vector(DataOutputStream out, V3 v) throws IOException {
    out.writeFloat(v.x());
    out.writeFloat(v.y());
    out.writeFloat(v.z());
  }
}
