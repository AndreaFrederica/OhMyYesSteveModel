package cc.sirrus.ysmlib.legacy.java;

import static org.junit.jupiter.api.Assertions.*;

import cc.sirrus.ysmlib.image.java.JavaImageProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BlockbenchExportTest {
  private final JavaBlockbenchExportProvider exporter =
      new JavaBlockbenchExportProvider(new JavaV3EnvelopeProvider(), new JavaImageProvider());

  private byte[] fixture(String name) throws Exception {
    try (var stream = getClass().getResourceAsStream("/historical/" + name + ".wire")) {
      assertNotNull(stream);
      return stream.readAllBytes();
    }
  }

  @Test
  void formatFiveQuadIsAnEditableMeshAndIsDiagnosed() throws Exception {
    String json =
        new String(exporter.exportWire(fixture("v5-face"), 364), StandardCharsets.UTF_8);
    assertTrue(json.contains("\"type\":\"mesh\""));
    assertTrue(json.contains("\"kind\":\"mesh_fallback\""));
    assertTrue(json.contains("\"vertices\""));
    assertTrue(json.contains("\"faces\""));
  }

  @Test
  void everyHistoricalVersionProducesACompleteProjectDocument() throws Exception {
    for (int version = 1; version <= 32; version++) {
      String json =
          new String(exporter.exportWire(fixture("v" + version), 0), StandardCharsets.UTF_8);
      assertTrue(json.startsWith("{\"meta\":"), "version " + version);
      assertTrue(json.endsWith("}\n"), "version " + version);
      assertTrue(json.contains("\"ysm\":{"), "version " + version);
      assertTrue(json.contains("\"inner_version\":" + version), "version " + version);
    }
  }

  @Test
  void nonFiniteHistoricalFloatsRemainValidJsonAndAreDiagnosed() throws Exception {
    String json =
        new String(
            exporter.exportWire(fixture("v32-infinite-animation"), 0), StandardCharsets.UTF_8);
    assertFalse(json.contains("Infinity"));
    assertFalse(json.contains("NaN"));
    assertTrue(json.contains("\"$float32\""));
    assertTrue(json.contains("\"kind\":\"non_finite_number\""));
    assertTrue(json.contains("\"source_wire_base64\""));
  }
}
