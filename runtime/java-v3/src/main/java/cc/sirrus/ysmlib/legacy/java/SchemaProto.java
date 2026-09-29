package cc.sirrus.ysmlib.legacy.java;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Minimal protobuf wire writer for the public model schema projection, never Java serialization.
 */
final class SchemaProto {
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  private void var(long v) {
    while ((v & ~127L) != 0) {
      out.write((int) v | 128);
      v >>>= 7;
    }
    out.write((int) v);
  }

  SchemaProto u(int field, long v) {
    var((long) field << 3);
    var(v);
    return this;
  }

  SchemaProto b(int field, boolean v) {
    return u(field, v ? 1 : 0);
  }

  SchemaProto f(int field, float v) {
    var(((long) field << 3) | 5);
    rawFloat(v);
    return this;
  }

  private void rawFloat(float v) {
    int bits = Float.floatToRawIntBits(v);
    for (int i = 0; i < 4; i++) out.write(bits >>> (i * 8));
  }

  SchemaProto s(int field, String s) {
    return bytes(field, s.getBytes(StandardCharsets.UTF_8));
  }

  SchemaProto bytes(int field, byte[] data) {
    var(((long) field << 3) | 2);
    var(data.length);
    out.writeBytes(data);
    return this;
  }

  SchemaProto m(int field, SchemaProto nested) {
    return bytes(field, nested.bytes());
  }

  SchemaProto floats(int field, List<Float> values) {
    var(((long) field << 3) | 2);
    var((long) values.size() * 4);
    for (float v : values) rawFloat(v);
    return this;
  }

  SchemaProto ints(int field, List<Integer> values) {
    var(((long) field << 3) | 2);
    var(
        values.stream()
            .mapToInt(
                v -> {
                  int size = 1;
                  while ((v & ~127) != 0) {
                    size++;
                    v >>>= 7;
                  }
                  return size;
                })
            .sum());
    for (int v : values) var(Integer.toUnsignedLong(v));
    return this;
  }

  byte[] bytes() {
    return out.toByteArray();
  }
}
