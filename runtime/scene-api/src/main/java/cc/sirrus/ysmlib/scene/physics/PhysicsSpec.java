package cc.sirrus.ysmlib.scene.physics;

import cc.sirrus.ysmlib.scene.Pose;
import cc.sirrus.ysmlib.scene.Rotation;
import cc.sirrus.ysmlib.scene.Vec3;
import java.util.List;
import java.util.Objects;

/** Complete primitive physics descriptions; source-format bone semantics stay in their profiles. */
public final class PhysicsSpec {
  private PhysicsSpec() {}
  public enum Shape { SPHERE, BOX, CAPSULE_Y }
  public enum Motion { STATIC, KINEMATIC, DYNAMIC }
  public enum JointType { SPRING_6DOF, GENERIC_6DOF, POINT_TO_POINT, CONE_TWIST, SLIDER, HINGE }

  public record World(Vec3 gravity, float stepSeconds, int solverIterations, boolean recordReplay, boolean kinematicFilter) {
    public World(Vec3 gravity,float stepSeconds,int solverIterations,boolean recordReplay) { this(gravity,stepSeconds,solverIterations,recordReplay,false); }
    public World(Vec3 gravity, float stepSeconds, int solverIterations) { this(gravity,stepSeconds,solverIterations,true); }
    public World {
      Objects.requireNonNull(gravity);
      finite(stepSeconds);
      if (stepSeconds <= 0 || stepSeconds > 1 || solverIterations < 1 || solverIterations > 1000)
        throw new IllegalArgumentException("Invalid simulation settings");
    }
    /** RS policy: reject pairs where exactly one object is kinematic, after group/mask filtering.
     * Selected before structure creation and retained by replay; default preserves ordinary Bullet contacts. */
    public World withKinematicFilter(boolean enabled) { return new World(gravity,stepSeconds,solverIterations,recordReplay,enabled); }
    public static World defaults() { return new World(new Vec3(0, -9.8f, 0), 1f/60, 10); }
    /** Live gameplay can run indefinitely without retaining a preview replay journal. */
    public World withoutReplay() { return new World(gravity,stepSeconds,solverIterations,false,kinematicFilter); }
  }

  /** Box dimensions are half extents; capsule dimensions are radius X and cylinder height Y. */
  public record Body(Shape shape, Vec3 dimensions, Pose pose, Motion motion, float mass,
                     float linearDamping, float angularDamping, float restitution, float friction,
                     float margin, int collisionGroup, int collisionMask) {
    public Body {
      Objects.requireNonNull(shape); Objects.requireNonNull(dimensions);
      Objects.requireNonNull(pose); Objects.requireNonNull(motion);
      nonnegative(mass); nonnegative(friction); nonnegative(margin);
      unit(linearDamping); unit(angularDamping); unit(restitution);
      if (dimensions.x() <= 0 || (shape == Shape.BOX && (dimensions.y() <= 0 || dimensions.z() <= 0))
          || (shape == Shape.CAPSULE_Y && dimensions.y() < 0))
        throw new IllegalArgumentException("Invalid collision shape dimensions");
      if (motion == Motion.DYNAMIC ? mass <= 0 : mass != 0)
        throw new IllegalArgumentException("Mass does not match motion type");
      mask(collisionGroup); mask(collisionMask);
    }
  }

  /** Frames are local to each body. -1 denotes the fixed world; both endpoints cannot be the world. */
  public record Joint(JointType type, int bodyA, int bodyB, Pose frameA, Pose frameB,
                      Vec3 linearLower, Vec3 linearUpper, Vec3 angularLower, Vec3 angularUpper,
                      Vec3 linearStiffness, Vec3 angularStiffness, float springDamping,
                      boolean disableLinkedCollision) {
    public Joint {
      Objects.requireNonNull(type); Objects.requireNonNull(frameA); Objects.requireNonNull(frameB);
      Objects.requireNonNull(linearLower); Objects.requireNonNull(linearUpper);
      Objects.requireNonNull(angularLower); Objects.requireNonNull(angularUpper);
      Objects.requireNonNull(linearStiffness); Objects.requireNonNull(angularStiffness);
      if(type==JointType.SPRING_6DOF) {
        nonnegative(linearStiffness.x()); nonnegative(linearStiffness.y()); nonnegative(linearStiffness.z());
        nonnegative(angularStiffness.x()); nonnegative(angularStiffness.y()); nonnegative(angularStiffness.z());
      }
      nonnegative(springDamping);
      if (bodyA < -1 || bodyB < -1 || bodyA == bodyB) throw new IllegalArgumentException("Invalid joint bodies");
    }
  }

  public sealed interface JointOptions permits SixDofOptions,ConeTwistOptions,SliderOptions,HingeOptions {}
  /** Per-axis limit correction (ERP) and softness (CFM) for generic/spring 6DOF joints. */
  public record SixDofOptions(Vec3 linearStopErp,Vec3 angularStopErp,Vec3 linearStopCfm,Vec3 angularStopCfm) implements JointOptions {
    public SixDofOptions {
      Objects.requireNonNull(linearStopErp);Objects.requireNonNull(angularStopErp);Objects.requireNonNull(linearStopCfm);Objects.requireNonNull(angularStopCfm);
      for(var v:List.of(linearStopErp,angularStopErp)){unit(v.x());unit(v.y());unit(v.z());}
      for(var v:List.of(linearStopCfm,angularStopCfm)){nonnegative(v.x());nonnegative(v.y());nonnegative(v.z());}
    }
  }
  public record ConeTwistOptions(float swing1,float swing2,float twist,float softness,float bias,float relaxation,
      float damping,float fixThreshold,boolean motorEnabled,float maxMotorImpulse,Rotation motorTarget) implements JointOptions {
    public ConeTwistOptions { for(float v:new float[]{swing1,swing2,twist,softness,bias,relaxation,damping,fixThreshold}) finite(v);nonnegative(maxMotorImpulse);Objects.requireNonNull(motorTarget); }
  }
  public record SliderOptions(boolean linearEnabled,float linearVelocity,float linearForce,boolean angularEnabled,
      float angularVelocity,float angularForce) implements JointOptions {
    public SliderOptions { finite(linearVelocity);finite(angularVelocity);nonnegative(linearForce);nonnegative(angularForce); }
  }
  public record HingeOptions(float softness,float bias,float relaxation,boolean motorEnabled,float velocity,float maxImpulse) implements JointOptions {
    public HingeOptions { finite(softness);finite(bias);finite(relaxation);finite(velocity);nonnegative(maxImpulse); }
  }

  /** Bullet soft-body configuration, including every PMX 2.1 config/cluster/material coefficient. */
  public record SoftConfig(float velocityCorrection, float damping, float drag, float lift,
                           float pressure, float volumeConservation, float friction, float poseMatching,
                           float rigidHardness, float kineticHardness, float softHardness, float anchorHardness,
                           float softRigidHardness, float softKineticHardness, float softSoftHardness,
                           float softRigidSplit, float softKineticSplit, float softSoftSplit,
                           int velocityIterations, int positionIterations, int driftIterations, int clusterIterations,
                           float linearStiffness, float angularStiffness, float volumeStiffness,
                           int aeroModel, int collisionFlags, int clusterCount, int bendingDistance,
                           boolean clustersEnabled,boolean randomizeConstraints) {
    public SoftConfig {
      for (float value : new float[]{velocityCorrection,damping,drag,lift,pressure,volumeConservation,
          friction,poseMatching,rigidHardness,kineticHardness,softHardness,anchorHardness,
          softRigidHardness,softKineticHardness,softSoftHardness,softRigidSplit,softKineticSplit,
          softSoftSplit,linearStiffness,angularStiffness,volumeStiffness}) finite(value);
      for (int value : new int[]{velocityIterations,positionIterations,driftIterations,clusterIterations,
          clusterCount,bendingDistance}) if (value < 0 || value > 10000) throw new IllegalArgumentException("Invalid soft-body iteration/count");
      if (aeroModel < 0 || aeroModel > 6) throw new IllegalArgumentException("Invalid aerodynamic model");
    }
    public static SoftConfig defaults() {
      return new SoftConfig(1,0,0,0,0,0,0.2f,0,1,0.1f,1,0.7f,
          0.1f,1,0.5f,0.5f,0.5f,0.5f,0,4,0,4,1,1,1,0,0x11,0,0,false,false);
    }
  }

  /** Indices form triangles, or pairs for a rope. Vertex IDs remain stable, including pinned nodes. */
  public record SoftBody(List<Vec3> vertices, List<Integer> indices, boolean rope, List<Integer> pins,
                         float mass, float margin, int collisionGroup, int collisionMask, SoftConfig config) {
    public SoftBody {
      vertices = List.copyOf(vertices); indices = List.copyOf(indices); pins = List.copyOf(pins);
      Objects.requireNonNull(config); nonnegative(mass); nonnegative(margin);
      mask(collisionGroup); mask(collisionMask);
      if (vertices.isEmpty() || indices.isEmpty() || indices.size() % (rope ? 2 : 3) != 0 || mass == 0)
        throw new IllegalArgumentException("Invalid soft-body topology");
      for (int index : indices) if (index < 0 || index >= vertices.size()) throw new IllegalArgumentException("Invalid soft-body index");
      for (int index : pins) if (index < 0 || index >= vertices.size()) throw new IllegalArgumentException("Invalid soft-body pin");
      for(int i=0;i<indices.size();i+=rope?2:3) {
        Vec3 edge=vertices.get(indices.get(i+1)).subtract(vertices.get(indices.get(i)));double length=edge.dot(edge);
        if(length<1e-30) throw new IllegalArgumentException("Zero-length soft-body edge");
        if(!rope) {
          Vec3 other=vertices.get(indices.get(i+2)).subtract(vertices.get(indices.get(i))),area=edge.cross(other);
          if(area.dot(area)<=1e-12*length*other.dot(other)) throw new IllegalArgumentException("Degenerate soft-body triangle");
        }
      }
    }
  }

  public record BodyState(Pose pose, Vec3 linearVelocity, Vec3 angularVelocity) {}
  public record Impulse(int body, Vec3 linear, Vec3 angular, boolean local) {
    public Impulse { if(body<0) throw new IllegalArgumentException("Invalid impulse body"); Objects.requireNonNull(linear); Objects.requireNonNull(angular); }
  }
  public record Force(int body, Vec3 linear, Vec3 angular, boolean local) {
    public Force { if(body<0) throw new IllegalArgumentException("Invalid force body"); Objects.requireNonNull(linear); Objects.requireNonNull(angular); }
  }

  private static void finite(float value) {
    if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite physics value");
  }
  private static void nonnegative(float value) { finite(value); if (value < 0) throw new IllegalArgumentException("Negative physics value"); }
  private static void unit(float value) { finite(value); if (value < 0 || value > 1) throw new IllegalArgumentException("Physics value outside [0,1]"); }
  private static void mask(int value) { if (value < 0 || value > 0xffff) throw new IllegalArgumentException("Invalid collision mask"); }
}
