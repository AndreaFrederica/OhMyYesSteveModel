package cc.sirrus.ysmlib.legacy.java;

import static cc.sirrus.ysmlib.legacy.LegacyDecodingException.Reason.*;

import cc.sirrus.ysmlib.legacy.LegacyDecodingException;
import cc.sirrus.ysmlib.legacy.V3EnvelopeProvider;
import io.airlift.compress.zstd.ZstdInputStream;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class JavaV3EnvelopeProvider implements V3EnvelopeProvider {
  private static final long HASH_SEED = Long.parseUnsignedLong("11409194399050398761"),
      XOR_SEED = Long.parseUnsignedLong("14994677486147417473");

  @Override
  public int profile() {
    return 1;
  }

  @Override
  public byte[] decode(ByteBuffer source, int plaintextLimit) throws IOException {
    if (source.remaining() > SOURCE_LIMIT
        || plaintextLimit < 4
        || plaintextLimit > 256 * 1024 * 1024)
      throw new LegacyDecodingException(RESOURCE_LIMIT, "Invalid V3 resource budget");
    if (source.remaining() < 12) throw new IOException("Truncated V3 header");
    byte[] data = new byte[source.remaining()];
    source.duplicate().get(data);
    byte[] magic = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'Y', 'S', 'G', 'P'};
    for (int i = 0; i < magic.length; i++)
      if (data[i] != magic[i]) throw new IOException("Not a V3 envelope");
    int offset = 7;
    while (offset < data.length && offset < 7 + 16_777_217 && data[offset] != 0) offset++;
    if (offset >= data.length || offset >= 7 + 16_777_217 || data.length - offset < 5)
      throw new IOException("Invalid V3 summary");
    offset++;
    if (ModifiedCityHash.le32(data, offset) != 3)
      throw new LegacyDecodingException(UNSUPPORTED_VERSION, "Unsupported envelope version");
    offset += 4;
    int footer = data.length - 64;
    if (offset >= footer) throw new IOException("Truncated V3 body");
    if (ModifiedCityHash.seeded(data, 0, data.length - 8, HASH_SEED)
        != ModifiedCityHash.le64(data, data.length - 8))
      throw new IOException("V3 envelope checksum mismatch");
    byte[] password = Arrays.copyOfRange(data, footer, footer + 56);
    var cipher = new HistoricalChaCha(password);
    for (int cursor = offset; cursor < footer; ) {
      int expected = cipher.chunkSize(), count = Math.min(expected, footer - cursor);
      cipher.decrypt(data, cursor, count, count == expected);
      cursor += count;
    }
    var random = new Mt19937(ModifiedCityHash.seeded(password, 0, password.length, XOR_SEED));
    long mask = 0;
    for (int i = offset; i < footer; i++) {
      if ((i - offset) % 8 == 0) mask = random.next();
      data[i] ^= (byte) mask;
      mask >>>= 8;
    }
    if (footer - offset < 2) throw new IOException("Truncated V3 salt");
    int salt = ((data[offset] & 255) | ((data[offset + 1] & 255) << 8)) & 1023;
    offset += 2 + salt;
    if (footer - offset < 5 || ModifiedCityHash.le32(data, offset) != 0xfd2fb528)
      throw new IOException("Invalid V3 zstd magic");
    int descriptor = data[offset + 4] & 255;
    boolean checksum = (descriptor & 4) != 0, single = (descriptor & 32) != 0;
    int flag = descriptor >>> 6,
        header =
            5
                + (single ? 0 : 1)
                + new int[] {0, 1, 2, 4}[descriptor & 3]
                + (flag == 0 ? (single ? 1 : 0) : (1 << flag));
    if (header > footer - offset) throw new IOException("Truncated zstd header");
    int start = offset;
    offset += header;
    boolean last = false;
    while (!last) {
      if (footer - offset < 3) throw new IOException("Truncated V3 block header");
      int
          dirty =
              (data[offset] & 255)
                  | ((data[offset + 1] & 255) << 8)
                  | ((data[offset + 2] & 255) << 16),
          type = (dirty >>> 5) & 3;
      if (type == 2) throw new IOException("Reserved V3 block type");
      last = (dirty & 128) != 0;
      int size = ((dirty & 31) << 16) | ((dirty >>> 8) ^ 0xd4e9);
      if (size > 128 * 1024) throw new IOException("V3 block exceeds zstd block limit");
      int clean = (last ? 1 : 0) | ((type == 0 ? 2 : type == 1 ? 1 : 0) << 1) | (size << 3);
      for (int i = 0; i < 3; i++) data[offset + i] = (byte) (clean >>> (i * 8));
      offset += 3;
      int payload = type == 1 ? 1 : size;
      if (payload > footer - offset) throw new IOException("Truncated V3 block");
      offset += payload;
    }
    if (checksum) offset += 4;
    if (offset != footer) throw new IOException("V3 frame trailing data or truncated checksum");
    try (var in = new ZstdInputStream(new ByteArrayInputStream(data, start, footer - start));
        var out = new ByteArrayOutputStream()) {
      byte[] chunk = new byte[64 * 1024];
      int total = 0, count;
      while ((count = in.read(chunk)) != -1) {
        if (count > plaintextLimit - total)
          throw new LegacyDecodingException(RESOURCE_LIMIT, "V3 plaintext budget exceeded");
        out.write(chunk, 0, count);
        total += count;
      }
      byte[] plain = out.toByteArray();
      if (plain.length < 4) throw new IOException("Truncated inner version");
      int version = ModifiedCityHash.le32(plain, 0);
      if (version < 1 || version > 32)
        throw new LegacyDecodingException(UNSUPPORTED_VERSION, "Unsupported inner version");
      return plain;
    } catch (RuntimeException malformed) {
      throw new IOException("Invalid V3 zstd payload", malformed);
    }
  }
}
