package cc.sirrus.ysmlib.legacy.java;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class DecodedWorkspaceTest {
  @Test
  void existingPngIsCopiedWithoutResizingOrTranscoding() throws Exception {
    byte[] original;
    try (var input = getClass().getResourceAsStream("/historical/v32.wire")) {
      original = input.readAllBytes();
    }
    var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, 0x83102030);
    var encoded = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", encoded);
    byte[] png = encoded.toByteArray();
    assertTrue(png.length < 128);
    byte[] pattern = {4, 0x10, 0x20, 0x30, (byte) 255, 1, 1, 1, 1};
    int offset = -1;
    for (int i = 0; i <= original.length - pattern.length; i++)
      if (Arrays.equals(pattern, Arrays.copyOfRange(original, i, i + pattern.length))) {
        offset = i;
        break;
      }
    assertTrue(offset >= 0);
    var wire = new java.io.ByteArrayOutputStream();
    wire.write(original, 0, offset);
    wire.write(png.length);
    wire.write(png);
    wire.write(new byte[] {1, 1, 2, 1});
    wire.write(original, offset + pattern.length, original.length - offset - pattern.length);
    var files = materialize(wire.toByteArray());
    assertArrayEquals(
        png,
        files.entrySet().stream()
            .filter(e -> e.getKey().endsWith(".png"))
            .findFirst()
            .orElseThrow()
            .getValue());
  }

  private Map<String, byte[]> materialize(byte[] wire) throws Exception {
    var result = new TreeMap<String, byte[]>();
    new JavaDecodedWorkspaceProvider()
        .materialize(
            ByteBuffer.wrap(wire),
            (path, data) -> {
              var bytes = new byte[data.remaining()];
              data.get(bytes);
              assertNull(result.put(path, bytes));
            });
    return result;
  }

  @Test
  void allHistoricalVersionsRetainRawImagesAndArchivalFields() throws Exception {
    for (int version = 1; version <= 32; version++) {
      byte[] wire;
      try (var stream = getClass().getResourceAsStream("/historical/v" + version + ".wire")) {
        wire = Objects.requireNonNull(stream).readAllBytes();
      }
      var files = materialize(wire);
      var replay = materialize(wire);
      assertEquals(files.keySet(), replay.keySet());
      files.forEach((path, bytes) -> assertArrayEquals(bytes, replay.get(path), path));
      assertTrue(files.containsKey("legacy/model.json"));
      String text = new String(files.get("legacy/model.json"), StandardCharsets.UTF_8);
      assertTrue(text.contains("LegacyV3Decoded"));
      if (version < 19) {
        assertTrue(text.contains("model_types"));
        assertTrue(text.contains("texture_hashes"));
      } else assertTrue(text.contains("order_info"));
      var tga =
          files.entrySet().stream()
              .filter(e -> e.getKey().endsWith(".tga"))
              .findFirst()
              .orElseThrow()
              .getValue();
      assertEquals(2, tga[2]);
      assertEquals(32, tga[16]);
      assertEquals(0x28, tga[17]);
      assertArrayEquals(new byte[] {0x30, 0x20, 0x10, (byte) 255}, Arrays.copyOfRange(tga, 18, 22));
      String geometry =
          files.entrySet().stream()
              .filter(e -> e.getKey().startsWith("geometry/") && e.getKey().endsWith(".json"))
              .map(e -> new String(e.getValue(), StandardCharsets.UTF_8))
              .findFirst()
              .orElseThrow();
      assertTrue(geometry.contains("visible_bounds_offset"));
      assertTrue(geometry.contains("pre_animation"));
    }
  }

  @Test
  void meshHasExactQuadLayoutAndSpecialFloatsRemainJson() throws Exception {
    try (var stream = getClass().getResourceAsStream("/historical/v5-face.wire")) {
      var files = materialize(stream.readAllBytes());
      var mesh =
          files.entrySet().stream()
              .filter(e -> e.getKey().endsWith(".mesh"))
              .findFirst()
              .orElseThrow()
              .getValue();
      assertEquals(100, mesh.length);
      var bytes = ByteBuffer.wrap(mesh).order(ByteOrder.LITTLE_ENDIAN);
      assertEquals(0x314d5359, bytes.getInt());
      assertEquals(1, bytes.getInt());
    }
    try (var stream = getClass().getResourceAsStream("/historical/v32-infinite-animation.wire")) {
      var files = materialize(stream.readAllBytes());
      assertTrue(
          files.values().stream()
              .map(b -> new String(b, StandardCharsets.UTF_8))
              .anyMatch(s -> s.contains("$float32")));
    }
  }
}
