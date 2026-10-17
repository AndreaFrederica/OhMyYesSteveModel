package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.physics.*;
import java.util.*;

/**
 * Binds an explicit FBX sidecar to the shared PhysicsWorld ABI.  The sidecar
 * owns source-node/body mapping; the solver remains the same native Bullet or
 * WASM provider used by MMD.  This first binding publishes node poses and is
 * deliberately independent of the FBX evaluator implementation.
 */
public final class FbxPhysicsBinding implements AutoCloseable {
  private final FbxDocument source;private final FbxPhysicsProfile profile;private final PhysicsWorld world;
  private final int[] bodyIds;private final PhysicsWorld.Snapshot initial;private final double stepSeconds;private boolean initialized,closed;private double lastSeconds=Double.NEGATIVE_INFINITY,accumulator;
  public FbxPhysicsBinding(FbxDocument source,FbxPhysicsProfile profile,PhysicsProvider provider,PhysicsSpec.World settings) {
    this.source=Objects.requireNonNull(source);this.profile=Objects.requireNonNull(profile);Objects.requireNonNull(provider);Objects.requireNonNull(settings);stepSeconds=settings.stepSeconds();
    world=provider.createWorld(settings);bodyIds=new int[profile.bodies().size()];
    try {
      for(int i=0;i<bodyIds.length;i++) {var b=profile.bodies().get(i);if(b.node()>=source.nodes().size())throw new IllegalArgumentException("FBX physics node outside source: "+b.node());bodyIds[i]=world.addBody(withPose(b.spec(),Pose.IDENTITY));}
      for(var joint:profile.joints()) world.addJoint(withBodies(joint.spec(),joint.bodyA()<0?-1:bodyIds[joint.bodyA()],joint.bodyB()<0?-1:bodyIds[joint.bodyB()]));
      initial=world.snapshot();
    } catch(RuntimeException|Error failure){world.close();throw failure;}
  }
  public PhysicsWorld world(){return world;}
  public synchronized void environment(PhysicsEnvironment environment){open();world.environment(environment);}
  /** Syncs animated kinematic bodies, advances fixed solver ticks, and returns poses keyed by FBX node. */
  public synchronized Map<Integer,Pose> step(FbxEvaluation.Frame frame,double seconds){
    open();Objects.requireNonNull(frame);if(frame.nodes().size()!=source.nodes().size())throw new IllegalArgumentException("FBX physics frame belongs to another source");
    if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Invalid FBX physics time");
    if(seconds<lastSeconds){world.restore(initial);initialized=false;accumulator=0;}double delta=initialized?Math.max(0,seconds-lastSeconds):0;lastSeconds=seconds;
    var animated=new Pose[profile.bodies().size()];
    for(int i=0;i<animated.length;i++){var b=profile.bodies().get(i);animated[i]=nodePose(frame.nodes().get(b.node())).multiply(b.spec().pose());if(!initialized||b.spec().motion()!=PhysicsSpec.Motion.DYNAMIC)world.setBodyPose(bodyIds[i],animated[i]);}
    if(!initialized){initialized=true;}
    else {accumulator+=delta;int steps=0;while(accumulator+1e-12>=stepSeconds&&steps++<4096){world.step();accumulator-=stepSeconds;}}
    var states=world.bodyStates();var out=new LinkedHashMap<Integer,Pose>();
    for(int i=0;i<animated.length;i++){var b=profile.bodies().get(i);out.put(b.node(),b.spec().motion()==PhysicsSpec.Motion.DYNAMIC?states.get(bodyIds[i]).pose():animated[i]);}
    return Map.copyOf(out);
  }
  private void open(){if(closed)throw new IllegalStateException("FBX physics binding is closed");}
  private static PhysicsSpec.Body withPose(PhysicsSpec.Body b,Pose pose){return new PhysicsSpec.Body(b.shape(),b.dimensions(),pose,b.motion(),b.mass(),b.linearDamping(),b.angularDamping(),b.restitution(),b.friction(),b.margin(),b.collisionGroup(),b.collisionMask());}
  private static PhysicsSpec.Joint withBodies(PhysicsSpec.Joint j,int a,int b){return new PhysicsSpec.Joint(j.type(),a,b,j.frameA(),j.frameB(),j.linearLower(),j.linearUpper(),j.angularLower(),j.angularUpper(),j.linearStiffness(),j.angularStiffness(),j.springDamping(),j.disableLinkedCollision());}
  static Pose nodePose(FbxDocument.Node node){var v=node.world();if(v.size()!=12)throw new IllegalArgumentException("FBX node matrix is not affine 3x4");
    // ufbx exposes the 3x4 node matrix column-major: [x-axis, y-axis,
    // z-axis, translation].  Keep the decomposition in that same basis;
    // reading it as rows transposes animated rotations and produces the
    // long skinning spikes seen in the in-game preview.
    double m00=v.get(0),m10=v.get(1),m20=v.get(2),m01=v.get(3),m11=v.get(4),m21=v.get(5),m02=v.get(6),m12=v.get(7),m22=v.get(8);double sx=Math.hypot(m00,Math.hypot(m10,m20)),sy=Math.hypot(m01,Math.hypot(m11,m21)),sz=Math.hypot(m02,Math.hypot(m12,m22));if(!(sx>1e-12&&sy>1e-12&&sz>1e-12))throw new IllegalArgumentException("FBX node transform is singular");m00/=sx;m01/=sy;m02/=sz;m10/=sx;m11/=sy;m12/=sz;m20/=sx;m21/=sy;m22/=sz;
    double trace=m00+m11+m22;double x,y,z,w;if(trace>0){double q=Math.sqrt(trace+1)*2;w=.25*q;x=(m21-m12)/q;y=(m02-m20)/q;z=(m10-m01)/q;}else if(m00>m11&&m00>m22){double q=Math.sqrt(1+m00-m11-m22)*2;w=(m21-m12)/q;x=.25*q;y=(m01+m10)/q;z=(m02+m20)/q;}else if(m11>m22){double q=Math.sqrt(1+m11-m00-m22)*2;w=(m02-m20)/q;x=(m01+m10)/q;y=.25*q;z=(m12+m21)/q;}else{double q=Math.sqrt(1+m22-m00-m11)*2;w=(m10-m01)/q;x=(m02+m20)/q;y=(m12+m21)/q;z=.25*q;}
    return new Pose(new Vec3((float)v.get(9),(float)v.get(10),(float)v.get(11)),Rotation.normalized((float)x,(float)y,(float)z,(float)w));
  }
  @Override public synchronized void close(){if(!closed){closed=true;world.close();}}
}
