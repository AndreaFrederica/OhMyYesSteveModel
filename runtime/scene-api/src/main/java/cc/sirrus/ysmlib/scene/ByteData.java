package cc.sirrus.ysmlib.scene;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Immutable owned encoded payload; callers cannot modify an asset through a buffer view. */
public final class ByteData {
  public static final ByteData EMPTY=new ByteData(new byte[0]);
  private final byte[] values;
  public ByteData(byte[] values) { this.values=values.clone(); }
  public int size() { return values.length; }
  public byte get(int i) { return values[i]; }
  public int unsigned(int i) { return Byte.toUnsignedInt(values[i]); }
  public byte[] copy() { return values.clone(); }
  public ByteBuffer view() { return ByteBuffer.wrap(values).asReadOnlyBuffer(); }
  @Override public boolean equals(Object other) { return other instanceof ByteData b && Arrays.equals(values,b.values); }
  @Override public int hashCode() { return Arrays.hashCode(values); }
}
