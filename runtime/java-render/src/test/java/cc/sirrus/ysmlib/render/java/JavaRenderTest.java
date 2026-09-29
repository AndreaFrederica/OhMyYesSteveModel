package cc.sirrus.ysmlib.render.java;

import static org.junit.jupiter.api.Assertions.*;

import cc.sirrus.ysmlib.render.*;
import cc.sirrus.ysmlib.render.Geometry.*;
import java.nio.*;
import java.util.*;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class JavaRenderTest {
  @Test
  void mirroredNonUniformScalePreservesNormalAndTangentHandedness() throws Exception {
    try (var state = new JavaModelState()) {
      var attributes = attrs();
      attributes[6] = -2;
      attributes[7] = 3;
      attributes[8] = 4;
      assertTrue(state.extract(model(false), attributes));
      assertFalse(state.uniform(0));
      assertEquals(-1, state.orientation(0));
      var vertices = new JavaRenderer().render(state, params(false));
      assertEquals(0x810000, vertices.get(0).normal());
      assertEquals(0x81000081, vertices.get(0).tangent());
      assertEquals(-2, vertices.get(1).x());
      assertEquals(3, vertices.get(2).y());
      assertEquals(4, vertices.get(4).z());
      var shadow = new JavaRenderer().render(state, params(true));
      assertEquals(0, shadow.get(0).tangent());
    }
  }

  @Test
  void rotatedPbrTangentUsesPoseAndKeepsUnitLength() throws Exception {
    try (var state = new JavaModelState()) {
      var attributes = attrs();
      attributes[2] = (float) (Math.PI / 2);
      assertTrue(state.extract(model(false), attributes));
      var vertex = new JavaRenderer().render(state, params(false)).get(0);
      assertEquals(0x7f0000, vertex.normal());
      assertEquals(0x81007f00, vertex.tangent());
    }
  }

  private static Geometry.Bone bone(String name, String parent, float z) {
    return new Geometry.Bone(
        name,
        parent,
        new V3(0, 0, 0),
        new V3(0, 0, 0),
        List.of(
            new Geometry.Cube(
                List.of(new V3(0, 0, z), new V3(1, 0, z), new V3(1, 1, z), new V3(0, 1, z)),
                List.of(
                    new Face(
                        List.of(0, 1, 2, 3),
                        List.of(new V2(0, 0), new V2(1, 0), new V2(1, 1), new V2(0, 1)),
                        new V3(0, 0, 1))))));
  }

  private static BakedModel model(boolean transparent) throws Exception {
    return new JavaBakeProvider()
        .bake(
            new Model(List.of(bone("root", null, 0), bone("child", "root", 1))),
            ByteBuffer.wrap(new byte[] {-1, -1, -1, (byte) (transparent ? 128 : 255)}),
            1,
            1,
            new Options(29, false, false, true));
  }

  private static float[] attrs() {
    float[] a = new float[28];
    for (int b = 0; b < 2; b++) {
      int o = b * 14;
      a[o + 6] = a[o + 7] = a[o + 8] = 1;
      a[o + 11] = 1;
      a[o + 12] = 0xffffff;
      a[o + 13] = 0xffff;
    }
    return a;
  }

  private static JavaRenderer.Parameters params(boolean shadow) {
    return new JavaRenderer.Parameters(
        new Matrix4f(),
        new Matrix3f(),
        new Matrix4f(),
        new Matrix4f(),
        0xffffffff,
        0x12345678,
        0x23456789,
        0x112233445566L,
        shadow);
  }

  @Test
  void parentTransformVisibilityAndLocatorSemantics() throws Exception {
    try (var state = new JavaModelState()) {
      var a = attrs();
      a[3] = 16;
      a[9] = 1;
      assertTrue(state.extract(model(false), a));
      assertEquals(List.of(1), state.renderBones());
      assertEquals(List.of(1), state.locators());
      assertEquals(-1, state.pose(1, new Matrix4f()).m30());
      a[9] = 0;
      a[10] = 1;
      assertTrue(state.extract(model(false), a));
      assertEquals(List.of(0), state.renderBones());
      a[6] = 0;
      assertTrue(state.extract(model(false), a));
      assertEquals(0, state.vertices());
    }
  }

  @Test
  void failedExtractInvalidatesPreviousFrameAndCloseIsFinal() throws Exception {
    var state = new JavaModelState();
    var a = attrs();
    assertTrue(state.extract(model(false), a));
    a[12] = -.5f;
    assertFalse(state.extract(model(false), a));
    assertFalse(state.valid());
    assertThrows(IllegalStateException.class, state::renderBones);
    state.close();
    assertThrows(IllegalStateException.class, () -> state.extract(model(false), attrs()));
  }

  @Test
  void sortsTransparentDepthButNotShadow() throws Exception {
    var state = new JavaModelState();
    assertTrue(state.extract(model(true), attrs()));
    var renderer = new JavaRenderer();
    assertEquals(1, renderer.render(state, params(false)).get(0).z());
    assertEquals(0, renderer.render(state, params(true)).get(0).z());
  }

  @Test
  void everyLayoutEncodesSameVerticesAndHonorsBufferBounds() throws Exception {
    var state = new JavaModelState();
    assertTrue(state.extract(model(false), attrs()));
    var renderer = new JavaRenderer();
    var vertices = renderer.render(state, params(false));
    assertEquals(8, vertices.size());
    assertEquals(0x7f0000, vertices.get(0).normal());
    for (var layout : JavaRenderer.Layout.values()) {
      var bytes =
          ByteBuffer.allocate(vertices.size() * layout.stride + 4).order(ByteOrder.LITTLE_ENDIAN);
      bytes.putInt(0xdeadbeef);
      renderer.write(vertices, layout, bytes);
      assertEquals(0xdeadbeef, bytes.getInt(0));
      assertEquals(0xffffffff, bytes.getInt(4 + 12));
      assertEquals(0x7f0000, bytes.getInt(4 + 32));
      assertEquals(0x12345678, bytes.getInt(4 + 28));
      if (layout != JavaRenderer.Layout.VANILLA) {
        assertEquals(0x5566, bytes.getShort(4 + 36) & 65535);
        assertEquals(.5f, bytes.getFloat(4 + layout.midOffset));
      }
      assertThrows(
          IllegalArgumentException.class,
          () -> renderer.write(vertices, layout, ByteBuffer.allocate(1)));
    }
  }

  @Test
  void rejectsNonFiniteDrawBeforeProducingVertices() throws Exception {
    var state = new JavaModelState();
    state.extract(model(false), attrs());
    var p = params(false);
    var invalid =
        new JavaRenderer.Parameters(
            new Matrix4f().m00(Float.NaN),
            p.normal(),
            p.view(),
            p.projection(),
            p.rgba(),
            p.light(),
            p.overlay(),
            p.entity(),
            false);
    assertThrows(IllegalArgumentException.class, () -> new JavaRenderer().render(state, invalid));
  }
}
