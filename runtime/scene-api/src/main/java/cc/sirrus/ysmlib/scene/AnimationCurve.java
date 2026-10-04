package cc.sirrus.ysmlib.scene;

import java.util.Objects;

/** Original interpolation is retained. Cubic tangents are derivatives per second. */
public final class AnimationCurve {
  public enum Interpolation { STEP,LINEAR,CUBICSPLINE,BEZIER }
  private final double[] times;
  private final FloatData values,inTangents,outTangents,bezier;
  private final int components;
  private final boolean quaternion;
  private final Interpolation interpolation;
  private final IntData heldSegments;

  /** Bezier data is x1,y1,x2,y2 per component (one curve for quaternion rotation), per key. */
  public AnimationCurve(double[] times,FloatData values,int components,boolean quaternion,Interpolation interpolation,
                        FloatData inTangents,FloatData outTangents,FloatData bezier) {
    this(times,values,components,quaternion,interpolation,inTangents,outTangents,bezier,new IntData());
  }
  /** heldSegments lists source-defined cuts: the preceding key is held until the destination time. */
  public AnimationCurve(double[] times,FloatData values,int components,boolean quaternion,Interpolation interpolation,
                        FloatData inTangents,FloatData outTangents,FloatData bezier,IntData heldSegments) {
    this.times=times.clone();this.values=Objects.requireNonNull(values);this.components=components;
    this.quaternion=quaternion;this.interpolation=Objects.requireNonNull(interpolation);
    this.inTangents=Objects.requireNonNull(inTangents);this.outTangents=Objects.requireNonNull(outTangents);this.bezier=Objects.requireNonNull(bezier);
    this.heldSegments=Objects.requireNonNull(heldSegments);
    if(components<1 || times.length==0 || (long)times.length*components!=values.size() || quaternion && components!=4)
      throw new IllegalArgumentException("Invalid curve shape");
    for(int i=0;i<this.times.length;i++) if(!Double.isFinite(this.times[i]) || this.times[i]<0 || i>0 && this.times[i]<=this.times[i-1])
      throw new IllegalArgumentException("Curve times must be finite, nonnegative and strictly increasing");
    if(interpolation==Interpolation.CUBICSPLINE && (inTangents.size()!=values.size() || outTangents.size()!=values.size()))
      throw new IllegalArgumentException("Missing cubic tangents");
    if(interpolation==Interpolation.BEZIER && bezier.size()!=(long)times.length*(quaternion?1:components)*4)
      throw new IllegalArgumentException("Missing Bezier curves");
    if(interpolation==Interpolation.BEZIER) for(int i=0;i<bezier.size();i++)
      if(bezier.get(i)<0 || bezier.get(i)>1) throw new IllegalArgumentException("Bezier control point outside [0,1]");
    for(int i=0;i<heldSegments.size();i++) if(heldSegments.get(i)<0 || heldSegments.get(i)>=times.length-1
        || i>0 && heldSegments.get(i)<=heldSegments.get(i-1)) throw new IllegalArgumentException("Invalid held segment");
    if(quaternion) for(int i=0;i<times.length;i++) {
      double norm=0;for(int k=0;k<4;k++) norm+=(double)values.get(i*4+k)*values.get(i*4+k);
      if(norm<1e-30) throw new IllegalArgumentException("Zero quaternion key");
    }
  }
  public int keys() { return times.length; }
  public double time(int key) { return times[key]; }
  public int components() { return components; }
  public boolean quaternion() { return quaternion; }
  public Interpolation interpolation() { return interpolation; }
  public FloatData values() { return values; }
  public FloatData inTangents() { return inTangents; }
  public FloatData outTangents() { return outTangents; }
  public FloatData bezier() { return bezier; }
  public IntData heldSegments() { return heldSegments; }
  public boolean holds(int segment) {
    int lo=0,hi=heldSegments.size()-1;
    while(lo<=hi) { int mid=(lo+hi)>>>1,v=heldSegments.get(mid);if(v==segment) return true;if(v<segment) lo=mid+1;else hi=mid-1; }
    return false;
  }
}
