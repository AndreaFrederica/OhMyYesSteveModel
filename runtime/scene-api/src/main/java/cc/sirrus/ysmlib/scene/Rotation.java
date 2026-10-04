package cc.sirrus.ysmlib.scene;

/** Unit quaternion, in XYZW order. Construction rejects invalid source data. */
public record Rotation(float x, float y, float z, float w) {
  public static final Rotation IDENTITY = new Rotation(0, 0, 0, 1);

  public Rotation {
    double norm = (double) x * x + (double) y * y + (double) z * z + (double) w * w;
    if (!Double.isFinite(norm) || Math.abs(norm - 1) > 0.002)
      throw new IllegalArgumentException("Expected a finite unit quaternion");
  }

  /** Explicit normalization for evaluated rotations; readers choose their own validation policy. */
  public static Rotation normalized(float x, float y, float z, float w) {
    double norm = Math.sqrt((double)x*x + (double)y*y + (double)z*z + (double)w*w);
    if (!Double.isFinite(norm) || norm < 1e-15) throw new IllegalArgumentException("Invalid quaternion");
    return new Rotation((float)(x/norm), (float)(y/norm), (float)(z/norm), (float)(w/norm));
  }
  public Rotation inverse() { return new Rotation(-x,-y,-z,w); }
  public Rotation multiply(Rotation b) {
    return normalized(w*b.x+x*b.w+y*b.z-z*b.y,w*b.y-x*b.z+y*b.w+z*b.x,
        w*b.z+x*b.y-y*b.x+z*b.w,w*b.w-x*b.x-y*b.y-z*b.z);
  }
  public Vec3 rotate(Vec3 v) {
    double tx=2*((double)y*v.z()-(double)z*v.y()),ty=2*((double)z*v.x()-(double)x*v.z()),tz=2*((double)x*v.y()-(double)y*v.x());
    return new Vec3((float)(v.x()+w*tx+y*tz-z*ty),(float)(v.y()+w*ty+z*tx-x*tz),(float)(v.z()+w*tz+x*ty-y*tx));
  }
  public static Rotation axisAngle(Vec3 axis,double radians) {
    double length=Math.sqrt((double)axis.x()*axis.x()+(double)axis.y()*axis.y()+(double)axis.z()*axis.z());
    if(!Double.isFinite(radians)) throw new IllegalArgumentException("Non-finite angle");
    if(length<1e-15) { if(Math.abs(radians)<1e-15) return IDENTITY;throw new IllegalArgumentException("Zero rotation axis"); }
    double s=Math.sin(radians/2)/length;return normalized((float)(axis.x()*s),(float)(axis.y()*s),(float)(axis.z()*s),(float)Math.cos(radians/2));
  }
  /** Shortest-arc interpolation, also allowing source-defined extrapolation ratios. */
  public static Rotation slerp(Rotation a,Rotation b,double t) {
    if(!Double.isFinite(t)) throw new IllegalArgumentException("Non-finite rotation ratio");
    double dot=(double)a.x*b.x+(double)a.y*b.y+(double)a.z*b.z+(double)a.w*b.w,sign=dot<0?-1:1;dot=Math.abs(dot);
    double u=1-t,v=t;
    if(dot<.9995) { double angle=Math.acos(Math.min(1,dot)),sin=Math.sin(angle);u=Math.sin((1-t)*angle)/sin;v=Math.sin(t*angle)/sin; }
    v*=sign;return normalized((float)(u*a.x+v*b.x),(float)(u*a.y+v*b.y),(float)(u*a.z+v*b.z),(float)(u*a.w+v*b.w));
  }
}
