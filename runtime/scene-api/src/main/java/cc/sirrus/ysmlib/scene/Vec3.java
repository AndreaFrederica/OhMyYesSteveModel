package cc.sirrus.ysmlib.scene;

/** Immutable vector in the coordinate space declared by its containing asset. */
public record Vec3(float x, float y, float z) {
  public static final Vec3 ZERO = new Vec3(0, 0, 0);
  public static final Vec3 ONE = new Vec3(1, 1, 1);

  public Vec3 {
    if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
      throw new IllegalArgumentException("Non-finite vector");
  }

  public Vec3 add(Vec3 v) { return new Vec3(x + v.x, y + v.y, z + v.z); }
  public Vec3 subtract(Vec3 v) { return new Vec3(x - v.x, y - v.y, z - v.z); }
  public Vec3 multiply(float value) { return new Vec3(x * value, y * value, z * value); }
  public float lengthSquared() { return x * x + y * y + z * z; }
  public double dot(Vec3 v) { return (double)x*v.x+(double)y*v.y+(double)z*v.z; }
  public Vec3 cross(Vec3 v) { return new Vec3(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x); }
  public Vec3 normalized() {
    double length=Math.sqrt(dot(this));
    if(length<1e-15) throw new IllegalArgumentException("Cannot normalize a zero vector");
    return new Vec3((float)(x/length),(float)(y/length),(float)(z/length));
  }
}
