package cc.sirrus.ysmlib.scene;

import java.util.Objects;

public record Transform(Vec3 translation,Rotation rotation,Vec3 scale) {
  public static final Transform IDENTITY=new Transform(Vec3.ZERO,Rotation.IDENTITY,Vec3.ONE);
  public Transform { Objects.requireNonNull(translation);Objects.requireNonNull(rotation);Objects.requireNonNull(scale); }
  public Matrix4 matrix() {
    float x=rotation.x(),y=rotation.y(),z=rotation.z(),w=rotation.w();
    float sx=scale.x(),sy=scale.y(),sz=scale.z();
    return new Matrix4((1-2*y*y-2*z*z)*sx,(2*x*y+2*z*w)*sx,(2*x*z-2*y*w)*sx,0,
        (2*x*y-2*z*w)*sy,(1-2*x*x-2*z*z)*sy,(2*y*z+2*x*w)*sy,0,
        (2*x*z+2*y*w)*sz,(2*y*z-2*x*w)*sz,(1-2*x*x-2*y*y)*sz,0,
        translation.x(),translation.y(),translation.z(),1);
  }
}
