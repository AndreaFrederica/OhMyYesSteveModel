package cc.sirrus.ysmlib.scene;

import java.nio.IntBuffer;
import java.util.Arrays;

public final class IntData {
  public static final IntData EMPTY=new IntData(new int[0]);
  private final int[] values;
  public IntData(int... values) { this.values=values.clone(); }
  public int size() { return values.length; }
  public int get(int index) { return values[index]; }
  public int[] copy() { return values.clone(); }
  public IntBuffer view() { return IntBuffer.wrap(values).asReadOnlyBuffer(); }
  @Override public boolean equals(Object other) { return other instanceof IntData d && Arrays.equals(values,d.values); }
  @Override public int hashCode() { return Arrays.hashCode(values); }
}
