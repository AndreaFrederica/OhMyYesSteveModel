package cc.sirrus.ysmlib.scene;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/** Immutable owned encoded payload; callers cannot modify an asset through a buffer view. */
public final class ByteData {
  public static final ByteData EMPTY=new ByteData(new byte[0]);
  private final byte[] values;
  public ByteData(byte[] values) { this.values=values.clone(); }
  /** Takes ownership of a freshly allocated immutable input buffer. */
  public static ByteData owned(byte[] values) {
    Objects.requireNonNull(values,"values");
    return new ByteData(values,false);
  }
  private ByteData(byte[] values,boolean copy) { this.values=copy?values.clone():values; }
  public int size() { return values.length; }
  public byte get(int i) { return values[i]; }
  public int unsigned(int i) { return Byte.toUnsignedInt(values[i]); }
  public byte[] copy() { return values.clone(); }
  public ByteBuffer view() { return ByteBuffer.wrap(values).asReadOnlyBuffer(); }
  /**
   * Runs a synchronous read-only operation over the owned bytes without
   * allocating a second array.  The callback must not retain or mutate the
   * supplied array; this is intentionally the only zero-copy escape hatch for
   * native decoders and other bounded readers.
   */
  public <T> T withReadOnlyArray(Function<byte[],T> reader) {
    return Objects.requireNonNull(reader,"reader").apply(values);
  }
  @Override public boolean equals(Object other) { return other instanceof ByteData b && Arrays.equals(values,b.values); }
  @Override public int hashCode() { return Arrays.hashCode(values); }
}
