package cc.sirrus.ysmlib.legacy.java;

import static org.junit.jupiter.api.Assertions.*;

import cc.sirrus.ysmlib.image.java.JavaImageProvider;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HistoricalWireTest {
  private final JavaLegacyImportProvider importer =
      new JavaLegacyImportProvider(new JavaV3EnvelopeProvider(), new JavaImageProvider());

  private byte[] fixture(String name) throws IOException {
    try (var stream = getClass().getResourceAsStream("/historical/" + name + ".wire")) {
      assertNotNull(stream);
      return stream.readAllBytes();
    }
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
        26, 27, 28, 29, 30, 31, 32
      })
  void readsIndependentCppWriter(int version) throws Exception {
    byte[] wire = fixture("v" + version);
    var result = importer.projectWire(wire, wire.length);
    assertEquals(version, result.innerVersion());
    assertEquals(4, result.payloads().size());
    assertEquals("MANIFEST", result.payloads().get(0).kind());
    assertEquals("MODEL_DATA", result.payloads().get(2).kind());
    assertEquals("BLOB_IMAGE", result.payloads().get(3).kind());
    for (int i = 0; i < 32; i++) assertEquals(i < 16 ? i : 0, result.modelId().get(i));
    assertThrows(
        IOException.class,
        () -> importer.projectWire(Arrays.copyOf(wire, wire.length - 1), wire.length));
    assertThrows(
        IOException.class,
        () -> importer.projectWire(Arrays.copyOf(wire, wire.length + 1), wire.length));
  }

  @Test
  void v5QuadSurvivesProjection() throws Exception {
    var empty = importer.projectWire(fixture("v5"), 0);
    var face = importer.projectWire(fixture("v5-face"), 0);
    assertTrue(
        face.payloads().get(2).bytes().remaining() > empty.payloads().get(2).bytes().remaining());
  }
}
