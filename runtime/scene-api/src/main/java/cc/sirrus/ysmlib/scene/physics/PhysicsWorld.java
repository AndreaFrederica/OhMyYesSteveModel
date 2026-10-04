package cc.sirrus.ysmlib.scene.physics;

import cc.sirrus.ysmlib.scene.Pose;
import cc.sirrus.ysmlib.scene.Vec3;
import java.util.List;
import java.util.ArrayList;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import cc.sirrus.ysmlib.scene.Rotation;

/** Single-owner, closeable simulation. A step is explicit and never implied by a render/query. */
public interface PhysicsWorld extends AutoCloseable {
  int addBody(PhysicsSpec.Body body);
  int addJoint(PhysicsSpec.Joint joint);
  /** Append a complete rigid structure. Joint endpoints use absolute world body IDs. */
  default StructureIds addStructure(List<PhysicsSpec.Body> bodies, List<PhysicsSpec.Joint> joints) {
    var bodyIds = new ArrayList<Integer>(bodies.size());
    var jointIds = new ArrayList<Integer>(joints.size());
    for (var body : bodies) bodyIds.add(addBody(body));
    for (var joint : joints) jointIds.add(addJoint(joint));
    return new StructureIds(bodyIds, jointIds);
  }
  record StructureIds(List<Integer> bodies, List<Integer> joints) {
    public StructureIds { bodies=List.copyOf(bodies); joints=List.copyOf(joints); }
  }
  void configureJoint(int joint,PhysicsSpec.JointOptions options);
  int addSoftBody(PhysicsSpec.SoftBody softBody);
  void anchorSoftBody(int softBody, int vertex, int rigidBody, boolean disableCollision);
  void setBodyPose(int body, Pose pose);
  /** Bulk pose publication hook; providers may override to avoid one native call per rigid body. */
  default void setBodyPoses(int[] bodies, Pose[] poses) {
    if (bodies == null || poses == null || bodies.length != poses.length) throw new IllegalArgumentException("Mismatched bulk pose arrays");
    for (int i = 0; i < bodies.length; i++) setBodyPose(bodies[i], poses[i]);
  }
  /** Packed pose input with already unit quaternions; preserve their scalar values and buffer positions.
   * Re-normalizing during transfer can change the subsequent constraint/contact trajectory. */
  default void setBodyPoses(IntBuffer bodies, FloatBuffer poses) {
    if (poses.remaining()!=bodies.remaining()*7) throw new IllegalArgumentException("Mismatched packed poses");
    var ids=bodies.duplicate();var values=poses.duplicate();
    while(ids.hasRemaining()) setBodyPose(ids.get(),new Pose(new Vec3(values.get(),values.get(),values.get()),new Rotation(values.get(),values.get(),values.get(),values.get())));
  }
  void setVelocity(int body, Vec3 linear, Vec3 angular);
  void applyImpulse(int body, Vec3 linear, Vec3 angular, boolean local);
  default void applyImpulses(List<PhysicsSpec.Impulse> impulses) {
    for (var impulse : impulses) applyImpulse(impulse.body(), impulse.linear(), impulse.angular(), impulse.local());
  }
  /** Packed linear(3), angular(3), local flag(0/1); input positions are preserved. */
  default void applyImpulses(IntBuffer bodies,FloatBuffer values) {
    var loads=packedLoads(bodies,values);var impulses=new ArrayList<PhysicsSpec.Impulse>(loads.size());
    for(var load:loads)impulses.add(new PhysicsSpec.Impulse(load.body(),load.linear(),load.angular(),load.local()));
    applyImpulses(impulses);
  }
  /** Accumulate forces for the next step; unlike impulses these are integrated over stepSeconds. */
  default void applyForces(List<PhysicsSpec.Force> forces) { throw new UnsupportedOperationException("Forces unavailable"); }
  /** Same packed layout as impulses; forces accumulate until the next explicit step. */
  default void applyForces(IntBuffer bodies,FloatBuffer values) { applyForces(packedLoads(bodies,values)); }
  private static List<PhysicsSpec.Force> packedLoads(IntBuffer bodies,FloatBuffer values) {
    if(bodies.remaining()>65536 || values.remaining()!=bodies.remaining()*7)throw new IllegalArgumentException("Mismatched packed loads");
    var ids=bodies.duplicate();var v=values.duplicate();var result=new ArrayList<PhysicsSpec.Force>(ids.remaining());
    while(ids.hasRemaining()) {
      int id=ids.get();var linear=new Vec3(v.get(),v.get(),v.get());var angular=new Vec3(v.get(),v.get(),v.get());float local=v.get();
      if(local!=0 && local!=1)throw new IllegalArgumentException("Invalid packed local flag");
      result.add(new PhysicsSpec.Force(id,linear,angular,local==1));
    }
    return result;
  }
  /** Explicit post-step speed guard for selected dynamic bodies; preserves velocity direction. */
  default void clampVelocities(int[] bodies,float maxLinear,float maxAngular) { throw new UnsupportedOperationException("Velocity guard unavailable"); }
  /** Packed IDs for a reusable post-step velocity guard; input position is preserved. */
  default void clampVelocities(IntBuffer bodies,float maxLinear,float maxAngular) {
    if(bodies.remaining()>65536)throw new IllegalArgumentException("Velocity guard body budget exceeded");
    int[] ids=new int[bodies.remaining()];bodies.duplicate().get(ids);clampVelocities(ids,maxLinear,maxAngular);
  }
  void resetBodyForces(int body);
  /** Temporarily drive an originally dynamic body from animation, retaining its mass/inertia for resumption. */
  void setKinematic(int body,boolean enabled);
  void setGravity(Vec3 gravity);
  /** Target for the next step. Only pinned vertices may be driven; queries return the last simulated state. */
  void setSoftBodyPin(int softBody,int vertex,Vec3 position);
  /** Packed targets for pinned vertices, applied by the next explicit step. Input positions are preserved. */
  default void setSoftBodyPins(int softBody, IntBuffer vertices, FloatBuffer positions) {
    if(positions.remaining()!=vertices.remaining()*3) throw new IllegalArgumentException("Mismatched packed pin targets");
    var ids=vertices.duplicate();var p=positions.duplicate();
    while(ids.hasRemaining()) setSoftBodyPin(softBody,ids.get(),new Vec3(p.get(),p.get(),p.get()));
  }
  void step();
  long stepIndex();
  /** -1 calls means the provider does not expose invocation counts. */
  default Statistics statistics() { return new Statistics(-1, stepIndex(), -1); }
  record Statistics(long nativeCalls, long steps, long replayBytes) {}
  List<PhysicsSpec.BodyState> bodyStates();
  /** Writes position(3), quaternion XYZW(4), linear(3), angular(3) for each body. */
  default int readBodyStates(FloatBuffer output) {
    var states=bodyStates();
    if (output.isReadOnly() || output.remaining()<states.size()*13) throw new IllegalArgumentException("Insufficient body state buffer");
    for (var state : states) {
      var p=state.pose().position(); var q=state.pose().rotation(); var l=state.linearVelocity(); var a=state.angularVelocity();
      output.put(p.x()).put(p.y()).put(p.z()).put(q.x()).put(q.y()).put(q.z()).put(q.w());
      output.put(l.x()).put(l.y()).put(l.z()).put(a.x()).put(a.y()).put(a.z());
    }
    return states.size();
  }
  List<Vec3> softBodyVertices(int softBody);
  List<Vec3> softBodyNormals(int softBody);
  default int readSoftBody(int softBody, FloatBuffer output, boolean normals) {
    var values=normals?softBodyNormals(softBody):softBodyVertices(softBody);
    if(output.isReadOnly() || output.remaining()<values.size()*3) throw new IllegalArgumentException("Insufficient soft-body buffer");
    for(var value:values) output.put(value.x()).put(value.y()).put(value.z());return values.size();
  }
  Snapshot snapshot();
  void restore(Snapshot snapshot);
  @Override void close();

  /** Provider-private checkpoint; only the creating world may restore it. bytes() estimates storage. */
  interface Snapshot { long stepIndex(); long bytes(); }
}
