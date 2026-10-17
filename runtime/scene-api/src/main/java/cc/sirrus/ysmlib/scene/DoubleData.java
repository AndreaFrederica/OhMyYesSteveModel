package cc.sirrus.ysmlib.scene;

import java.nio.DoubleBuffer;
import java.util.Arrays;

/** Owned finite source data for formats whose curves, transforms and geometry use double precision. */
public final class DoubleData {
  public static final DoubleData EMPTY=new DoubleData();
  private final double[] values;
  private final float[] floatValues;
  public DoubleData(double... values) {
    this.values=values.clone();this.floatValues=null;for(double v:this.values) if(!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite source data");
  }
  /** Internal format-reader fast path. The caller must not mutate the array after publication. */
  public static DoubleData owned(double[] values) {
    for(double v:values) if(!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite source data");
    // FBX mesh/UV/morph arrays are commonly millions of values that already
    // came from single-precision storage. Preserve the public double view but
    // retain those exact values in four bytes per scalar. Small arrays stay
    // double-backed so matrices and curve keys remain lossless without a
    // needless conversion allocation.
    if(values.length>=4096) {
      float[] compact=new float[values.length];boolean exact=true;
      for(int i=0;i<values.length;i++) { float f=(float)values[i];compact[i]=f;if((double)f!=values[i]) { exact=false;break; } }
      if(exact) return new DoubleData(compact);
    }
    return new DoubleData(values,false);
  }
  /** Internal evaluated-frame fast path. Float results stay compact until a caller explicitly requests doubles. */
  public static DoubleData ownedFloats(float[] values) {
    if(values==null) throw new NullPointerException("values");
    for(float v:values) if(!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite source data");
    return new DoubleData(values);
  }
  private DoubleData() { this.values=new double[0];this.floatValues=null; }
  private DoubleData(double[] values,boolean copy) { this.values=copy?values.clone():values;this.floatValues=null; }
  private DoubleData(float[] values) { this.values=null;this.floatValues=values; }
  public int size() { return values!=null?values.length:floatValues.length; }
  public double get(int index) { return values!=null?values[index]:floatValues[index]; }
  public double[] copy() { if(values!=null)return values.clone();double[] out=new double[floatValues.length];for(int i=0;i<out.length;i++)out[i]=floatValues[i];return out; }
  /** Returns a float projection, sharing the compact backing array when one exists. */
  public FloatData floatView() {
    if(floatValues!=null) return FloatData.owned(floatValues);
    float[] out=new float[values.length];for(int i=0;i<out.length;i++){float f=(float)values[i];if(!Float.isFinite(f))throw new IllegalArgumentException("Value outside float range");out[i]=f;}
    return FloatData.owned(out);
  }
  public DoubleBuffer view() { return DoubleBuffer.wrap(copy()).asReadOnlyBuffer(); }
  @Override public boolean equals(Object other) { if(!(other instanceof DoubleData d)||size()!=d.size())return false;for(int i=0;i<size();i++)if(Double.compare(get(i),d.get(i))!=0)return false;return true; }
  @Override public int hashCode() { int result=1;for(int i=0;i<size();i++)result=31*result+Double.hashCode(get(i));return result; }
}
