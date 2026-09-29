package cc.sirrus.ysmlib.render.java;

import static org.junit.jupiter.api.Assertions.*;

import cc.sirrus.ysmlib.render.*;
import cc.sirrus.ysmlib.render.Geometry.*;
import java.io.IOException;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JavaBakeTest {
  @Test
  void historicalUvBoundaryProfilesRemainDistinct() throws Exception {
    var uv = Collections.nCopies(4, new V2(1, 1));
    var cube =
        new Geometry.Cube(
            FACE.positions(), List.of(new Face(List.of(0, 1, 2, 3), uv, new V3(0, 0, 1))));
    byte[] pixels = new byte[4 * 4 * 4];
    Arrays.fill(pixels, (byte) 255);
    for (int version : new int[] {0, 6, 17, 28, 29}) {
      var baked =
          baker.bake(
              new Model(List.of(bone("root", null, cube))),
              ByteBuffer.wrap(pixels),
              4,
              4,
              new Options(version, false, false, false));
      var partition = baked.bones().get(0).partitions().get(1);
      if (version == 6) {
        assertTrue(partition.isEmpty());
        continue;
      }
      var values = partition.get(0).quads().get(0).uv();
      if (version == 17) assertEquals(Collections.nCopies(4, new V2(.75f, .75f)), values);
      else
        assertEquals(
            List.of(new V2(.75f, 1), new V2(1, 1), new V2(1, .75f), new V2(.75f, .75f)), values);
    }
  }

  private static Geometry.Cube box(boolean inverted) {
    var positions =
        List.of(
            new V3(0, 0, 0),
            new V3(1, 0, 0),
            new V3(1, 1, 0),
            new V3(0, 1, 0),
            new V3(0, 0, 1),
            new V3(1, 0, 1),
            new V3(1, 1, 1),
            new V3(0, 1, 1));
    int[][] indices = {
      {0, 3, 2, 1}, {4, 5, 6, 7}, {0, 4, 7, 3}, {1, 2, 6, 5}, {0, 1, 5, 4}, {3, 7, 6, 2}
    };
    var normals =
        List.of(
            new V3(0, 0, -1),
            new V3(0, 0, 1),
            new V3(-1, 0, 0),
            new V3(1, 0, 0),
            new V3(0, -1, 0),
            new V3(0, 1, 0));
    var faces = new ArrayList<Face>();
    for (int i = 0; i < 6; i++) {
      var ids = new ArrayList<Integer>();
      for (int id : indices[i]) ids.add(id);
      if (inverted) Collections.reverse(ids);
      faces.add(new Face(ids, FACE.faces().get(0).uv(), normals.get(i).mul(inverted ? -1 : 1)));
    }
    return new Geometry.Cube(positions, faces);
  }

  @Test
  void sixFaceAndInvertedCubesReserveCorrectCullingCapacity() throws Exception {
    for (boolean inverted : new boolean[] {false, true}) {
      var source = new Model(List.of(bone("root", null, box(inverted))));
      var opaque = bake(source, 255, false).bones().get(0);
      assertEquals(inverted ? 20 : 12, opaque.vertices(0));
      var transparent = bake(source, 128, false).bones().get(0);
      assertEquals(inverted ? 20 : 24, transparent.vertices(inverted ? 3 : 2));
      var forced =
          baker.bake(
              source,
              ByteBuffer.wrap(new byte[] {-1, -1, -1, 127}),
              1,
              1,
              new Options(29, true, false, true));
      assertEquals(inverted ? 20 : 12, forced.bones().get(0).vertices(3));
    }
  }

  private final JavaBakeProvider baker = new JavaBakeProvider();
  private static final V3 ZERO = new V3(0, 0, 0);
  private static final Geometry.Cube FACE =
      new Geometry.Cube(
          List.of(new V3(0, 0, 0), new V3(1, 0, 0), new V3(1, 1, 0), new V3(0, 1, 0)),
          List.of(
              new Face(
                  List.of(0, 1, 2, 3),
                  List.of(new V2(0, 0), new V2(1, 0), new V2(1, 1), new V2(0, 1)),
                  new V3(0, 0, 1))));

  private static Bone bone(String name, String parent, Geometry.Cube... cubes) {
    return new Bone(name, parent, ZERO, ZERO, List.of(cubes));
  }

  private BakedModel bake(Model model, int alpha, boolean force) throws IOException {
    return baker.bake(
        model,
        ByteBuffer.wrap(new byte[] {1, 2, 3, (byte) alpha}),
        1,
        1,
        new Options(29, false, force, true));
  }

  @Test
  void stableHierarchyAndCacheRoundTrip() throws Exception {
    var model =
        bake(
            new Model(
                List.of(bone("child", "root", FACE), bone("root", null), bone("other", null))),
            255,
            false);
    assertArrayEquals(new short[] {1, 0, 2}, model.sortedIndices());
    assertEquals(2, model.bones().get(0).subtreeEnd());
    assertEquals(0, model.bones().get(1).parent());
    assertEquals(1, model.bones().get(1).depth());
    assertEquals(model, baker.decode(ByteBuffer.wrap(baker.encode(model))));
    var q = model.bones().get(1).partitions().get(1).get(0).quads().get(0);
    assertEquals(new V4(1, 0, 0, -1), q.tangent());
    assertEquals(1, q.windingSign());
  }

  @Test
  void alphaClassificationAndForcedTransparentRetainEmptyFaces() throws Exception {
    var source = new Model(List.of(bone("root", null, FACE)));
    assertEquals(4, bake(source, 255, false).bones().get(0).vertices(1));
    assertEquals(4, bake(source, 128, false).bones().get(0).vertices(2));
    assertEquals(0, bake(source, 0, false).bones().get(0).vertices(2));
    assertEquals(4, bake(source, 0, true).bones().get(0).vertices(2));
  }

  @Test
  void rejectsCyclesDuplicatesMissingParentsAndInvalidNumbers() {
    for (var source :
        List.of(
            new Model(List.of(bone("a", "b"), bone("b", "a"))),
            new Model(List.of(bone("a", null), bone("a", null))),
            new Model(List.of(bone("a", "missing"))),
            new Model(List.of(new Bone("a", null, new V3(Float.NaN, 0, 0), ZERO, List.of())))))
      assertThrows(IOException.class, () -> bake(source, 255, false));
  }

  @Test
  void deepHierarchyDoesNotUseJavaCallStack() throws Exception {
    var bones = new ArrayList<Bone>();
    for (int i = 0; i < 20000; i++) bones.add(bone("b" + i, i == 0 ? null : "b" + (i - 1)));
    var result = bake(new Model(bones), 255, false);
    assertEquals(19999, result.bones().get(19999).depth());
    assertEquals(result, baker.decode(ByteBuffer.wrap(baker.encode(result))));
  }

  @Test
  void cacheRejectsTruncationTrailingBytesAndBrokenHierarchy() throws Exception {
    var bytes = baker.encode(bake(new Model(List.of(bone("root", null, FACE))), 255, false));
    for (int n = 0; n < bytes.length; n++) {
      var truncated = Arrays.copyOf(bytes, n);
      assertThrows(IOException.class, () -> baker.decode(ByteBuffer.wrap(truncated)));
    }
    assertThrows(
        IOException.class,
        () -> baker.decode(ByteBuffer.wrap(Arrays.copyOf(bytes, bytes.length + 1))));
    ByteBuffer.wrap(bytes).putInt(13, 0);
    assertThrows(IOException.class, () -> baker.decode(ByteBuffer.wrap(bytes)));
  }
}
