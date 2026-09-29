package cc.sirrus.ysmlib.render.natives;

import static org.junit.jupiter.api.Assertions.*;

import cc.sirrus.ysmlib.render.*;
import cc.sirrus.ysmlib.render.Geometry.*;
import cc.sirrus.ysmlib.render.java.*;
import java.nio.*;
import java.util.*;
import org.joml.*;
import org.junit.jupiter.api.Test;

/** Native whole-draw output must byte-match the Java packed writer for representative geometry. */
class NativeRenderParityTest {
  @org.junit.jupiter.api.BeforeEach void requireNativeLibrary() {
    org.junit.jupiter.api.Assumptions.assumeTrue(!System.getProperty("ysm.test.renderLibrary", "").isBlank(),
        "Use nativeTest -PrenderLibrary=<path> for accelerator parity");
  }
  @Test
  void packedNativeDrawMatchesJavaForAllLayouts() throws Exception {
    String library = System.getProperty("ysm.test.renderLibrary", "");
    assertFalse(library.isBlank(), "nativeTest requires -PrenderLibrary");
    var model = model();
    var attributes = new float[14];
    attributes[6] = attributes[7] = attributes[8] = 1;
    attributes[11] = 1;
    attributes[12] = 0xffffff;
    attributes[13] = 0xffff;
    var parameters = new Renderer.Parameters(new Matrix4f(), new Matrix3f(), new Matrix4f(),
        new Matrix4f(), 0xffd0c0b0, 0x12345678, 0x23456789, 9, false);
    var baseline = new JavaRenderer();
    try (var state = new NativeRenderProvider(java.nio.file.Path.of(library)).createState()) {
      assertTrue(state.extract(model, attributes));
      var expected = baseline.render(state, parameters);
      for (var layout : Renderer.Layout.values()) {
        var javaBytes = ByteBuffer.allocateDirect(expected.size() * layout.stride).order(ByteOrder.LITTLE_ENDIAN);
        baseline.write(expected, layout, javaBytes);
        var nativeBytes = ByteBuffer.allocateDirect(expected.size() * layout.stride).order(ByteOrder.LITTLE_ENDIAN);
        new NativeRenderProvider(java.nio.file.Path.of(library)).renderer()
            .renderInto(state, parameters, layout, nativeBytes);
        assertTrue(javaBytes.remaining() > 0);
        assertEquals(0, nativeBytes.position());
        assertEquals(javaBytes, nativeBytes, layout.name());
      }
    }
  }

  static BakedModel model() {
    var positions = List.of(new V3(0, 0, 0), new V3(1, 0, 0), new V3(1, 1, 0), new V3(0, 1, 0));
    var quad = new BakedModel.Quad(List.of(0, 1, 2, 3),
        List.of(new V2(0, 0), new V2(1, 0), new V2(1, 1), new V2(0, 1)),
        new V3(0, 0, 1), new V4(1, 0, 0, 1), new V3(.5f, .5f, 0), 0, 1);
    var cube = new BakedModel.Cube(positions, List.of(quad), 1);
    var partitions = new ArrayList<List<BakedModel.Cube>>();
    for (int i = 0; i < 4; i++) partitions.add(i == 1 ? List.of(cube) : List.of());
    return new BakedModel(List.of(new BakedModel.Bone(0, -1, 1, 0, new V3(0, 0, 0),
        true, partitions)), true);
  }

  @Test
  void selectedArmsAndBackgroundMatchJavaAndRespectDestinationBounds() {
    var provider = new NativeRenderProvider(java.nio.file.Path.of(System.getProperty("ysm.test.renderLibrary")));
    var bone = model().bones().get(0);
    var model = new BakedModel(List.of(bone,
        new BakedModel.Bone(1, -1, 2, 0, bone.pivot(), true, bone.partitions()),
        new BakedModel.Bone(2, -1, 3, 0, bone.pivot(), true, bone.partitions())), true);
    var attributes = new float[42];
    for (int i = 0; i < 3; i++) {
      int o = i * 14;
      attributes[o + 3] = i * 8;
      attributes[o + 6] = attributes[o + 7] = attributes[o + 8] = 1;
      attributes[o + 11] = i + 1;
      attributes[o + 12] = 0xffffff;
      attributes[o + 13] = 0xffff;
    }
    var parameters = new Renderer.Parameters(new Matrix4f(), new Matrix3f(), new Matrix4f(),
        new Matrix4f(), 0xffffffff, 0x12345678, 0x23456789, 9, false);
    try (var state = provider.createState()) {
      // FirstPerson hides the head after a full-body draw; animation can hide more bones.
      // Reuse the same state while the packed frame shrinks and grows.
      for (int locator : new int[]{0, 1, 3, 0, 2, 3, 1}) {
        assertTrue(state.extract(model, locator == 0 ? attributes
            : LocatorVisibility.select(model, attributes, locator)));
        assertEquals(locator == 0 ? List.of(0, 1, 2) : List.of(locator - 1), state.renderBones());
        assertEquals(locator == 0 ? 12 : 4, state.vertices());
        for (var layout : Renderer.Layout.values()) {
          int bytes = state.vertices() * layout.stride;
          var expected = ByteBuffer.allocateDirect(bytes);
          new JavaRenderer().renderInto(state, parameters, layout, expected);
          var actual = ByteBuffer.allocateDirect(bytes + 32);
          for (int i = 0; i < actual.capacity(); i++) actual.put(i, (byte) 0x5a);
          actual.position(16).limit(16 + bytes);
          provider.renderer().renderInto(state, parameters, layout, actual);
          assertEquals(16, actual.position());
          assertEquals(expected, actual, "locator=" + locator + ", " + layout);
          actual.clear();
          for (int i = 0; i < 16; i++) {
            assertEquals((byte) 0x5a, actual.get(i));
            assertEquals((byte) 0x5a, actual.get(16 + bytes + i));
          }
        }
        assertEquals("native-cpp-render-v1 (packed)", provider.id(), "Must not pass by Java fallback");
      }
      assertEquals(7L * Renderer.Layout.values().length, provider.successfulNativeDraws());
    }
  }

  @Test
  void truncatedFrameHeaderIsRejectedWithoutPublishingOutput() {
    new NativeRenderProvider(java.nio.file.Path.of(System.getProperty("ysm.test.renderLibrary")));
    var geometry = ByteBuffer.allocateDirect(20).order(ByteOrder.LITTLE_ENDIAN);
    geometry.putInt(0x52534d59).putInt(1).putInt(0).putInt(0).putInt(0).flip();
    for (int bytes : new int[]{0, 255, 256, 257, 258, 259}) {
      var output = ByteBuffer.allocateDirect(8);
      for (int i = 0; i < 8; i++) output.put(i, (byte) 0x5a);
      assertEquals(1, NativeRenderProvider.nRender(geometry, ByteBuffer.allocateDirect(bytes),
          36, 0, output));
      for (int i = 0; i < 8; i++) assertEquals((byte) 0x5a, output.get(i));
    }
  }

  @Test
  void finiteCameraMatricesReachNativeDraw() {
    var provider = new NativeRenderProvider(java.nio.file.Path.of(System.getProperty("ysm.test.renderLibrary")));
    float[] attributes = {0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0xffffff, 0xffff};
    var pose = new Matrix4f().translate(.123f, -.72f, 3.41f).rotateXYZ(.12f, .48f, -.3f);
    var parameters = new Renderer.Parameters(pose, pose.normal(new Matrix3f()),
        new Matrix4f().rotateXYZ(.53f, 1.2f, -.05f),
        new Matrix4f().perspective(1.2f, 16f / 9, .05f, 1024f), -1, 0, 0, 0, false);
    try (var state = provider.createState()) {
      assertTrue(state.extract(model(), attributes));
      var output = ByteBuffer.allocateDirect(state.vertices() * 36).order(ByteOrder.LITTLE_ENDIAN);
      provider.renderer().renderInto(state, parameters, Renderer.Layout.VANILLA, output);
      var expected = new JavaRenderer().render(state, parameters);
      for (int i = 0; i < expected.size(); i++) {
        var vertex = expected.get(i);
        assertEquals(vertex.x(), output.getFloat(i * 36), 0.000003f);
        assertEquals(vertex.y(), output.getFloat(i * 36 + 4), 0.000003f);
        assertEquals(vertex.z(), output.getFloat(i * 36 + 8), 0.000003f);
        assertEquals(vertex.normal(), output.getInt(i * 36 + 32));
      }
      assertEquals("native-cpp-render-v1 (packed)", provider.id());
      assertEquals(1, provider.successfulNativeDraws());
    }
  }
}
