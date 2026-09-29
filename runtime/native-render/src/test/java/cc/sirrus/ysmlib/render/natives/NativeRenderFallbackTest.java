package cc.sirrus.ysmlib.render.natives;

import static org.junit.jupiter.api.Assertions.*;

import cc.sirrus.ysmlib.render.Renderer;
import cc.sirrus.ysmlib.render.java.JavaRenderer;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class NativeRenderFallbackTest {
  @Test
  void rejectedDrawIsRetriedWithoutLosingVerticesOrRetryingBrokenAccelerator() {
    for (int status : new int[]{1, 2, 15, -1}) {
      var calls = new AtomicInteger();
      var provider = new NativeRenderProvider((geometry, frame, stride, mid, output) -> {
        calls.incrementAndGet();
        if (status == -1) throw new UnsatisfiedLinkError("missing draw symbol");
        return status;
      });
      float[] attributes = {0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0xffffff, 0xffff};
      var parameters = new Renderer.Parameters(new Matrix4f(), new Matrix3f(), new Matrix4f(),
          new Matrix4f(), -1, 0, 0, 0, false);
      try (var state = provider.createState()) {
        assertTrue(state.extract(NativeRenderParityTest.model(), attributes));
        for (var layout : Renderer.Layout.values()) {
          int bytes = state.vertices() * layout.stride;
          var expected = ByteBuffer.allocateDirect(bytes);
          new JavaRenderer().renderInto(state, parameters, layout, expected);
          var actual = ByteBuffer.allocateDirect(bytes + 16);
          for (int i = 0; i < actual.capacity(); i++) actual.put(i, (byte) 0x5a);
          actual.position(8).limit(8 + bytes);
          provider.renderer().renderInto(state, parameters, layout, actual);
          assertEquals(8, actual.position());
          assertEquals(expected, actual);
          actual.clear();
          for (int i = 0; i < 8; i++) {
            assertEquals((byte) 0x5a, actual.get(i));
            assertEquals((byte) 0x5a, actual.get(bytes + 8 + i));
          }
        }
        assertEquals(1, calls.get());
        assertEquals(0, provider.successfulNativeDraws());
        assertEquals("java-render", provider.id());
        var invalid = new Renderer.Parameters(new Matrix4f().m00(Float.NaN), new Matrix3f(),
            new Matrix4f(), new Matrix4f(), -1, 0, 0, 0, false);
        var untouched = ByteBuffer.allocateDirect(state.vertices() * 36);
        assertThrows(IllegalArgumentException.class, () -> provider.renderer().renderInto(
            state, invalid, Renderer.Layout.VANILLA, untouched));
        for (int i = 0; i < untouched.capacity(); i++) assertEquals(0, untouched.get(i));
      }
    }
  }
}
