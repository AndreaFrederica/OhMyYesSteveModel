package cc.sirrus.ysmlib.scene;

import java.nio.DoubleBuffer;
import java.util.Arrays;

/** Owned finite source data for formats whose curves, transforms and geometry use double precision. */
public final class DoubleData {
  public static final DoubleData EMPTY=new DoubleData();
  private final double[] values;
  public DoubleData(double... values) {
    this.values=values.clone();for(double v:this.values) if(!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite source data");
  }
  public int size() { return values.length; }
  public double get(int index) { return values[index]; }
  public double[] copy() { return values.clone(); }
  public DoubleBuffer view() { return DoubleBuffer.wrap(values).asReadOnlyBuffer(); }
  @Override public boolean equals(Object other) { return other instanceof DoubleData d && Arrays.equals(values,d.values); }
  @Override public int hashCode() { return Arrays.hashCode(values); }
}
