package cc.sirrus.ysmlib.legacy.java;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class V3EnvelopeTest {
  @Test
  void cityHashMatchesHistoricalWriterVectors() {
    int[] sizes = {0, 1, 3, 4, 7, 8, 16, 17, 32, 33, 63, 64, 65, 127, 128, 1024, 8191};
    String[] expected = {
      "8042932239147081391",
      "14662329999069294316",
      "803572144813152280",
      "15713305407892776351",
      "13844931084561017689",
      "16340748013193004988",
      "17737655933971482886",
      "10788145213889431210",
      "17947090291158778479",
      "1688872210173529929",
      "9538431262500756175",
      "4565498937205348967",
      "11260580286896762533",
      "15616308421445669944",
      "10089192292417589676",
      "10644972106623026718",
      "7064877764580148889"
    };
    for (int i = 0; i < sizes.length; i++) {
      byte[] data = new byte[sizes[i]];
      for (int j = 0; j < data.length; j++) data[j] = (byte) (j * 131 + 17);
      assertEquals(
          expected[i],
          Long.toUnsignedString(
              ModifiedCityHash.seeded(
                  data, 0, data.length, Long.parseUnsignedLong("11409194399050398761"))),
          "length " + sizes[i]);
    }
  }

  private byte[] fixture() throws IOException {
    try (var in = getClass().getResourceAsStream("/legacy_v3_dynamic_vector.ysm")) {
      return in.readAllBytes();
    }
  }

  @Test
  void decodesFrozenHistoricalDynamicCipherVector() throws Exception {
    var source = ByteBuffer.wrap(fixture());
    var plain = new JavaV3EnvelopeProvider().decode(source, 12004);
    assertEquals(0, source.position());
    assertEquals(12004, plain.length);
    assertEquals(1, ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).getInt());
    var random = new Mt19937(0xD15EA5E);
    for (int i = 4; i < plain.length; i++)
      assertEquals((byte) random.next(), plain[i], "offset " + i);
  }

  @Test
  void rejectsCorruptionTruncationAndOutputOverflow() throws Exception {
    var decoder = new JavaV3EnvelopeProvider();
    byte[] original = fixture();
    assertThrows(IOException.class, () -> decoder.decode(ByteBuffer.wrap(original), 12003));
    for (int cut : new int[] {0, 7, 12, 64, 12094})
      assertThrows(
          IOException.class,
          () -> decoder.decode(ByteBuffer.wrap(Arrays.copyOf(original, cut)), 12004));
    original[100] ^= 1;
    assertThrows(IOException.class, () -> decoder.decode(ByteBuffer.wrap(original), 12004));
  }
}
