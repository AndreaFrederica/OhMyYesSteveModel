package cc.sirrus.ysmlib.scene;

import java.util.Objects;

/** Rigid transform. Scale belongs to the asset/host transform, not a Bullet body transform. */
public record Pose(Vec3 position, Rotation rotation) {
  public static final Pose IDENTITY = new Pose(Vec3.ZERO, Rotation.IDENTITY);
  public Pose { Objects.requireNonNull(position); Objects.requireNonNull(rotation); }
  public Pose multiply(Pose child) { return new Pose(position.add(rotation.rotate(child.position)),rotation.multiply(child.rotation)); }
  public Pose inverse() { var inverse=rotation.inverse();return new Pose(inverse.rotate(position.multiply(-1)),inverse); }
  public Matrix4 matrix() { return new Transform(position,rotation,Vec3.ONE).matrix(); }
}
