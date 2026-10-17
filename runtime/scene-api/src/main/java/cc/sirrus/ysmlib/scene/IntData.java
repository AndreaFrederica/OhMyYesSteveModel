package cc.sirrus.ysmlib.scene;

import java.nio.IntBuffer;
import java.util.Arrays;

public final class IntData {
  public static final IntData EMPTY=new IntData(new int[0]);
  private final int[] values;
  private volatile int[] range;
  public IntData(int... values) { this.values=values.clone(); }
  /** Internal format-reader fast path. The caller must not mutate the array after publication. */
  public static IntData owned(int[] values) { return new IntData(values,false); }
  private IntData(int[] values,boolean copy) { this.values=copy?values.clone():values; }
  public int size() { return values.length; }
  public int get(int index) { return values[index]; }
  public int[] copy() { return values.clone(); }
  public IntBuffer view() { return IntBuffer.wrap(values).asReadOnlyBuffer(); }
  /** Cached immutable index-domain validation, shared by every frame and visibility projection. */
  public boolean within(int exclusiveMaximum) {
    if(values.length==0)return true;var r=range;if(r==null){int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;for(int value:values){min=Math.min(min,value);max=Math.max(max,value);}range=r=new int[]{min,max};}
    return r[0]>=0&&r[1]<exclusiveMaximum;
  }
  @Override public boolean equals(Object other) { return other instanceof IntData d && Arrays.equals(values,d.values); }
  @Override public int hashCode() { return Arrays.hashCode(values); }
}
