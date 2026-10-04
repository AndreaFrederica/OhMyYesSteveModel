package cc.sirrus.ysmlib.scene;

import java.nio.FloatBuffer;

/** Owned immutable scalar data. Read-only views never expose a mutable backing array. */
public final class FloatData {
  public static final FloatData EMPTY=new FloatData(new float[0]);
  private final float[] values;
  /** Optional normalized UNORM8 storage.  Most model textures are 8-bit; retaining
   * four Java floats per sample made a handful of 4K textures consume gigabytes. */
  private final byte[] normalized8;
  public FloatData(float... values) {
    this.values=values.clone();
    this.normalized8=null;
    for(float v:this.values) if(!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite asset data");
  }
  private FloatData(float[] values, byte[] normalized8, boolean owned) {
    this.values=values == null ? null : (owned ? values : values.clone());
    this.normalized8=normalized8;
  }
  private FloatData(float[] values, boolean owned) { this(values, null, owned); }
  public static FloatData ownedNormalized8(byte[] values) {
    if (values == null) throw new NullPointerException("values");
    return new FloatData(null, values.clone(), true);
  }
  /**
   * Takes ownership of an already validated array. Format decoders use this at a
   * memory-sensitive boundary after they have finished writing the array; callers
   * must never mutate the array after this call.
   */
  public static FloatData owned(float[] values) {
    if(values==null) throw new NullPointerException("values");
    for(float v:values) if(!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite asset data");
    return new FloatData(values,true);
  }
  public int size() { return values != null ? values.length : normalized8.length; }
  /** Approximate retained backing-store bytes, excluding transient views. */
  public long storageBytes() { return values != null ? (long) values.length * Float.BYTES : normalized8.length; }
  public float get(int index) {
    if (values != null) return values[index];
    return (normalized8[index] & 0xff) / 255f;
  }
  public float[] copy() {
    if (values != null) return values.clone();
    float[] result=new float[normalized8.length];
    for (int i=0;i<result.length;i++) result[i]=(normalized8[i]&0xff)/255f;
    return result;
  }
  public FloatBuffer view() { return FloatBuffer.wrap(copy()).asReadOnlyBuffer(); }
  /** Copies into caller-owned scratch storage without exposing the immutable backing data. */
  public void copyTo(float[] output,int offset) {
    java.util.Objects.checkFromIndexSize(offset,size(),output.length);
    if(normalized8==null)System.arraycopy(values,0,output,offset,values.length);
    else for(int i=0;i<size();i++)output[offset+i]=get(i);
  }
  @Override public boolean equals(Object other) {
    if (!(other instanceof FloatData d) || size()!=d.size()) return false;
    for (int i=0;i<size();i++) if (Float.compare(get(i),d.get(i))!=0) return false;
    return true;
  }
  @Override public int hashCode() {
    int result=1; for (int i=0;i<size();i++) result=31*result+Float.hashCode(get(i)); return result;
  }
}
