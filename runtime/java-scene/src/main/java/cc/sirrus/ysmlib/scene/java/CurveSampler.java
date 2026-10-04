package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.AnimationCurve;
import java.util.Objects;

/** Stateless sampling; seeks do not depend on previous calls or wall-clock time. */
public final class CurveSampler {
  private CurveSampler() {}
  public static float[] sample(AnimationCurve curve,double seconds) {
    float[] out=new float[curve.components()];sample(curve,seconds,out,0);return out;
  }
  public static void sample(AnimationCurve c,double seconds,float[] out,int offset) {
    Objects.requireNonNull(c);Objects.checkFromIndexSize(offset,c.components(),out.length);
    if(!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite sample time");
    int lo=0,hi=c.keys()-1,n=c.components();
    if(seconds<=c.time(0)) { copy(c,0,out,offset);return; }
    if(seconds>=c.time(hi)) { copy(c,hi,out,offset);return; }
    while(lo+1<hi) { int mid=(lo+hi)>>>1;if(seconds<c.time(mid)) hi=mid;else lo=mid; }
    if(c.interpolation()==AnimationCurve.Interpolation.STEP || c.holds(lo)) { copy(c,lo,out,offset);return; }
    double duration=c.time(hi)-c.time(lo),t=(seconds-c.time(lo))/duration;
    if(c.quaternion() && c.interpolation()!=AnimationCurve.Interpolation.CUBICSPLINE) {
      if(c.interpolation()==AnimationCurve.Interpolation.BEZIER) t=bezier(c,hi,0,t);
      slerp(c,lo,hi,t,out,offset);return;
    }
    for(int k=0;k<n;k++) {
      double a=c.values().get(lo*n+k),b=c.values().get(hi*n+k);
      if(c.interpolation()==AnimationCurve.Interpolation.CUBICSPLINE) {
        double t2=t*t,t3=t2*t;
        out[offset+k]=(float)((2*t3-3*t2+1)*a+(t3-2*t2+t)*duration*c.outTangents().get(lo*n+k)
            +(-2*t3+3*t2)*b+(t3-t2)*duration*c.inTangents().get(hi*n+k));
      } else {
        double u=c.interpolation()==AnimationCurve.Interpolation.BEZIER?bezier(c,hi,k,t):t;
        out[offset+k]=(float)(a+(b-a)*u);
      }
    }
    // glTF cubic quaternion interpolation uses component Hermite then normalization, without sign flips.
    if(c.quaternion()) normalize(out,offset);
  }
  private static void copy(AnimationCurve c,int key,float[] out,int offset) {
    for(int k=0;k<c.components();k++) out[offset+k]=c.values().get(key*c.components()+k);
    if(c.quaternion()) normalize(out,offset);
  }
  private static void slerp(AnimationCurve c,int a,int b,double t,float[] out,int o) {
    double[] q=new double[4],r=new double[4];double qn=0,rn=0;
    for(int i=0;i<4;i++) { q[i]=c.values().get(a*4+i);r[i]=c.values().get(b*4+i);qn+=q[i]*q[i];rn+=r[i]*r[i]; }
    if(qn<1e-30 || rn<1e-30) throw new IllegalArgumentException("Zero quaternion key");
    double dot=0;for(int i=0;i<4;i++) { q[i]/=Math.sqrt(qn);r[i]/=Math.sqrt(rn);dot+=q[i]*r[i]; }
    if(dot<0) { dot=-dot;for(int i=0;i<4;i++) r[i]=-r[i]; }
    double u=1-t,v=t;
    if(dot<0.9995) { double angle=Math.acos(Math.min(1,dot)),sin=Math.sin(angle);u=Math.sin((1-t)*angle)/sin;v=Math.sin(t*angle)/sin; }
    for(int i=0;i<4;i++) out[o+i]=(float)(q[i]*u+r[i]*v);
    normalize(out,o);
  }
  private static void normalize(float[] out,int o) {
    double norm=0;for(int k=0;k<4;k++) norm+=(double)out[o+k]*out[o+k];
    if(norm<1e-30 || !Double.isFinite(norm)) throw new IllegalArgumentException("Animation produced invalid quaternion");
    norm=Math.sqrt(norm);for(int k=0;k<4;k++) out[o+k]/=(float)norm;
  }
  /** VMD stores the incoming segment's control points on its destination key. Solve x before y. */
  private static double bezier(AnimationCurve c,int key,int component,double x) {
    int base=(key*(c.quaternion()?1:c.components())+component)*4;
    double x1=c.bezier().get(base),y1=c.bezier().get(base+1),x2=c.bezier().get(base+2),y2=c.bezier().get(base+3);
    double lo=0,hi=1;
    for(int i=0;i<40;i++) { double u=(lo+hi)*.5;if(cubic(u,x1,x2)<x) lo=u;else hi=u; }
    return cubic((lo+hi)*.5,y1,y2);
  }
  private static double cubic(double t,double a,double b) { double s=1-t;return 3*s*s*t*a+3*s*t*t*b+t*t*t; }
}
