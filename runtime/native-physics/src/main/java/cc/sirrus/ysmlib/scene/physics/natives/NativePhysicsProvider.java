package cc.sirrus.ysmlib.scene.physics.natives;

import cc.sirrus.ysmlib.scene.Pose;
import cc.sirrus.ysmlib.scene.Rotation;
import cc.sirrus.ysmlib.scene.Vec3;
import cc.sirrus.ysmlib.scene.physics.*;
import java.nio.file.Path;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Optional Bullet accelerator. The caller owns its world, clock and bounded replay journal. */
public final class NativePhysicsProvider implements PhysicsProvider {
  private static final int ABI = 5;
  public static final long FEATURE_BITS = 0xffL;

  public NativePhysicsProvider(Path library) {
    System.load(library.toAbsolutePath().normalize().toString());
    if (nAbiVersion()!=ABI || nFeatureBits()!=FEATURE_BITS || nScalarBits()!=32 || nThreadCount()!=1)
      throw new UnsatisfiedLinkError("ysmlib physics ABI/precision/capability mismatch");
    try(var w=createWorld(PhysicsSpec.World.defaults().withoutReplay())) {
      w.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(.5f,0,0),Pose.IDENTITY,
          PhysicsSpec.Motion.DYNAMIC,1,0,0,0,.5f,.04f,1,0));
      var ids=java.nio.ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
      var loads=java.nio.ByteBuffer.allocateDirect(28).order(ByteOrder.nativeOrder()).asFloatBuffer();
      w.applyImpulses(ids,loads);w.applyForces(ids,loads);
      w.clampVelocities(ids,100,100);
      w.step();
      if(Math.abs(w.bodyStates().get(0).linearVelocity().y()+9.8f/60)>1e-5)
        throw new UnsatisfiedLinkError("ysmlib physics trajectory self-check failed");
    }
  }
  @Override public String id() { return "bullet-3.25-native-v5"; }
  @Override public PhysicsCapabilities capabilities() { return new PhysicsCapabilities(id(),ABI,FEATURE_BITS,true); }
  @Override public PhysicsWorld createWorld(PhysicsSpec.World settings) { return new World(settings); }
  public int scalarBits() { return nScalarBits(); }
  public int solverThreads() { return nThreadCount(); }

  private enum Operation { BODY, JOINT, IMPULSES, FORCES, CLAMP, OPTIONS, ANCHOR, POSE, POSES, VELOCITY, IMPULSE, RESET, KINEMATIC, GRAVITY, PIN, STEP }
  /** All arrays are constructed internally or copied before being placed in a command. */
  private record Command(Operation op,float[] values,int[] args) implements ReplayCommand {
    public long bytes() { return 80L+4L*(values.length+args.length); }
    public int run(long p) {
      return switch(op) {
        case BODY -> nBody(p,values);
        case JOINT -> nJoint(p,values);
        case IMPULSES -> nImpulses(p,args,values);
        case FORCES -> nForces(p,args,values);
        case CLAMP -> nClamp(p,args,values[0],values[1]);
        case OPTIONS -> nConfigureJoint(p,args[0],values);
        case ANCHOR -> nSoftAnchor(p,args[0],args[1],args[2],args[3]!=0);
        case POSE -> nPose(p,args[0],values);
        case POSES -> nPoses(p,args,values);
        case VELOCITY -> nVelocity(p,args[0],values);
        case IMPULSE -> nImpulse(p,args[0],values,args[1]!=0);
        case RESET -> nReset(p,args[0]);
        case KINEMATIC -> nKinematic(p,args[0],args[1]!=0);
        case GRAVITY -> nGravity(p,values);
        case PIN -> nSoftPin(p,args[0],args[1],values);
        case STEP -> nStep(p);
      };
    }
  }
  private static final float[] NO_FLOATS=new float[0];

  private interface ReplayCommand {
    int run(long p);
    long bytes();
  }

  private static final class World implements PhysicsWorld {
    final PhysicsSpec.World settings;
    final Object identity=new Object();
    final ArrayList<ReplayCommand> history=new ArrayList<>();
    final ArrayList<Integer> softVertexCounts=new ArrayList<>();
    long nativeWorld,steps,historyBytes,nativeCalls;
    int bodyCount,jointCount;
    boolean failed;
    World(PhysicsSpec.World s) { settings=Objects.requireNonNull(s);nativeWorld=allocate(s); }
    private static long allocate(PhysicsSpec.World s) {
      long p=nCreate(s.gravity().x(),s.gravity().y(),s.gravity().z(),s.stepSeconds());
      if(p==0) throw new IllegalStateException("Native world creation failed");
      if(nIterations(p,s.solverIterations())<0) { nDestroy(p);throw new IllegalStateException("Native solver settings rejected"); }
      try {
        if(nKinematicFilter(p,s.kinematicFilter())<0) throw new IllegalStateException("Native collision policy rejected");
      } catch(RuntimeException|Error failure) { nDestroy(p);throw failure; }
      return p;
    }
    void open() { if(nativeWorld==0 || failed) throw new IllegalStateException("Physics world is closed or failed"); }
    int checked(int result) {
      if(result==-1) throw new IllegalArgumentException("Native physics input rejected");
      if(result<0) { failed=true;throw new IllegalStateException("Native physics simulation failed; reset is required ("+result+")"); }
      return result;
    }
    int execute(ReplayCommand c) {
      open();
      if(settings.recordReplay() && (historyBytes+c.bytes()>64L*1024*1024 || history.size()>=4_000_000))
        throw new IllegalStateException("Physics replay history budget exceeded");
      if(settings.recordReplay()) history.ensureCapacity(history.size()+1);
      nativeCalls++;
      int result=checked(c.run(nativeWorld));
      if(settings.recordReplay()) { history.add(c);historyBytes+=c.bytes(); }
      return result;
    }
    int send(Operation op,float[] values,int...args) { return execute(new Command(op,values,args)); }
    @Override public synchronized int addBody(PhysicsSpec.Body b) {
      int id=send(Operation.BODY,packBody(b)); bodyCount++;return id;
    }
    @Override public synchronized int addJoint(PhysicsSpec.Joint j) {
      int id=send(Operation.JOINT,packJoint(j));jointCount++;return id;
    }
    @Override public synchronized StructureIds addStructure(List<PhysicsSpec.Body> bodies,List<PhysicsSpec.Joint> joints) {
      open(); if(bodies.size()>65536-bodyCount || joints.size()>65536-jointCount)throw new IllegalArgumentException("Rigid structure budget exceeded");
      float[] b=new float[bodies.size()*20],j=new float[joints.size()*37];
      for(int i=0;i<bodies.size();i++)System.arraycopy(packBody(bodies.get(i)),0,b,i*20,20);
      for(int i=0;i<joints.size();i++)System.arraycopy(packJoint(joints.get(i)),0,j,i*37,37);
      var bi=new ArrayList<Integer>(bodies.size());var ji=new ArrayList<Integer>(joints.size());
      for(int i=0;i<bodies.size();i++)bi.add(bodyCount+i);for(int i=0;i<joints.size();i++)ji.add(jointCount+i);
      var result=new StructureIds(bi,ji);
      execute(new BuildCommand(b,j));bodyCount+=bodies.size();jointCount+=joints.size();
      return result;
    }
    @Override public synchronized void configureJoint(int j,PhysicsSpec.JointOptions options) {
      float[] c;
      if(options instanceof PhysicsSpec.SixDofOptions x) {
        c=new float[13];putVec(c,1,x.linearStopErp());putVec(c,4,x.angularStopErp());putVec(c,7,x.linearStopCfm());putVec(c,10,x.angularStopCfm());
      } else if(options instanceof PhysicsSpec.ConeTwistOptions x) c=new float[]{3,x.swing1(),x.swing2(),x.twist(),x.softness(),x.bias(),x.relaxation(),x.damping(),x.fixThreshold(),x.motorEnabled()?1:0,x.maxMotorImpulse(),x.motorTarget().x(),x.motorTarget().y(),x.motorTarget().z(),x.motorTarget().w()};
      else if(options instanceof PhysicsSpec.SliderOptions x) c=new float[]{4,x.linearEnabled()?1:0,x.linearVelocity(),x.linearForce(),x.angularEnabled()?1:0,x.angularVelocity(),x.angularForce()};
      else if(options instanceof PhysicsSpec.HingeOptions x) c=new float[]{5,x.softness(),x.bias(),x.relaxation(),x.motorEnabled()?1:0,x.velocity(),x.maxImpulse()};
      else throw new IllegalArgumentException("Unknown joint options");
      send(Operation.OPTIONS,c,j);
    }
    @Override public synchronized int addSoftBody(PhysicsSpec.SoftBody s) {
      Objects.requireNonNull(s);
      long bytes=4L*(34+3L*s.vertices().size()+s.indices().size()+s.pins().size());
      if(bytes>64L*1024*1024) throw new IllegalArgumentException("Soft-body transfer budget exceeded");
      float[] v=new float[Math.multiplyExact(s.vertices().size(),3)];for(int i=0;i<s.vertices().size();i++)putVec(v,i*3,s.vertices().get(i));
      int[] indices=s.indices().stream().mapToInt(Integer::intValue).toArray(),pins=s.pins().stream().mapToInt(Integer::intValue).toArray();
      var c=s.config();float[] cfg={c.velocityCorrection(),c.damping(),c.drag(),c.lift(),c.pressure(),c.volumeConservation(),c.friction(),c.poseMatching(),c.rigidHardness(),c.kineticHardness(),c.softHardness(),c.anchorHardness(),c.softRigidHardness(),c.softKineticHardness(),c.softSoftHardness(),c.softRigidSplit(),c.softKineticSplit(),c.softSoftSplit(),c.velocityIterations(),c.positionIterations(),c.driftIterations(),c.clusterIterations(),c.linearStiffness(),c.angularStiffness(),c.volumeStiffness(),c.aeroModel(),c.collisionFlags(),c.clusterCount(),c.bendingDistance(),c.clustersEnabled()?1:0,c.randomizeConstraints()?1:0,s.collisionGroup(),s.collisionMask(),s.margin()};
      softVertexCounts.ensureCapacity(softVertexCounts.size()+1);
      int id=execute(new SoftCommand(v,indices,pins,s.rope(),s.mass(),cfg));softVertexCounts.add(s.vertices().size());return id;
    }
    @Override public synchronized void anchorSoftBody(int s,int v,int b,boolean disable) { send(Operation.ANCHOR,NO_FLOATS,s,v,b,disable?1:0); }
    @Override public synchronized void setBodyPose(int b,Pose p) { float[] c=new float[7];putPose(c,0,p);send(Operation.POSE,c,b); }
    @Override public synchronized void setBodyPoses(int[] ids,Pose[] poses) {
      open();
      if(ids==null || poses==null || ids.length!=poses.length || ids.length>65536)throw new IllegalArgumentException("Invalid bulk pose arrays");
      if(ids.length==0)return;
      float[] c=new float[ids.length*7];for(int i=0;i<ids.length;i++)putPose(c,i*7,poses[i]);send(Operation.POSES,c,ids.clone());
    }
    @Override public synchronized void setBodyPoses(IntBuffer ids,FloatBuffer poses) {
      open();Objects.requireNonNull(ids);Objects.requireNonNull(poses);
      if(!ids.isDirect() || !poses.isDirect() || ids.order()!=ByteOrder.nativeOrder() || poses.order()!=ByteOrder.nativeOrder() || ids.remaining()>65536 || poses.remaining()!=ids.remaining()*7)
        throw new IllegalArgumentException("Packed pose sync needs matching direct native-order buffers");
      if(ids.remaining()==0)return;
      if(settings.recordReplay()) {
        int[] i=new int[ids.remaining()];float[] v=new float[poses.remaining()];ids.duplicate().get(i);poses.duplicate().get(v);send(Operation.POSES,v,i);
      } else {nativeCalls++;checked(nPosesDirect(nativeWorld,ids.slice(),poses.slice()));}
    }
    @Override public synchronized void setVelocity(int b,Vec3 l,Vec3 a) { send(Operation.VELOCITY,vectors(l,a),b); }
    @Override public synchronized void applyImpulse(int b,Vec3 l,Vec3 a,boolean local) { send(Operation.IMPULSE,vectors(l,a),b,local?1:0); }
    @Override public synchronized void applyImpulses(List<PhysicsSpec.Impulse> impulses) {
      open(); if(impulses.size()>65536)throw new IllegalArgumentException("Impulse batch budget exceeded");
      int[] ids=new int[impulses.size()];float[] c=new float[ids.length*7];
      for(int i=0;i<ids.length;i++){var x=impulses.get(i);ids[i]=x.body();putVec(c,i*7,x.linear());putVec(c,i*7+3,x.angular());c[i*7+6]=x.local()?1:0;}
      send(Operation.IMPULSES,c,ids);
    }
    @Override public synchronized void applyForces(List<PhysicsSpec.Force> forces) {
      open();if(forces.size()>65536)throw new IllegalArgumentException("Force batch budget exceeded");
      int[] ids=new int[forces.size()];float[] c=new float[ids.length*7];
      for(int i=0;i<ids.length;i++){var x=forces.get(i);ids[i]=x.body();putVec(c,i*7,x.linear());putVec(c,i*7+3,x.angular());c[i*7+6]=x.local()?1:0;}
      send(Operation.FORCES,c,ids);
    }
    @Override public synchronized void applyImpulses(IntBuffer ids,FloatBuffer values) { packedLoads(ids,values,false); }
    @Override public synchronized void applyForces(IntBuffer ids,FloatBuffer values) { packedLoads(ids,values,true); }
    private void packedLoads(IntBuffer ids,FloatBuffer values,boolean force) {
      open();Objects.requireNonNull(ids);Objects.requireNonNull(values);
      if(!ids.isDirect() || !values.isDirect() || ids.order()!=ByteOrder.nativeOrder() || values.order()!=ByteOrder.nativeOrder() || ids.remaining()>65536 || values.remaining()!=ids.remaining()*7)
        throw new IllegalArgumentException("Packed loads need matching direct native-order buffers");
      if(ids.remaining()==0)return;
      if(settings.recordReplay()) {
        int[] i=new int[ids.remaining()];float[] v=new float[values.remaining()];ids.duplicate().get(i);values.duplicate().get(v);send(force?Operation.FORCES:Operation.IMPULSES,v,i);
      }else{nativeCalls++;checked(nLoadsDirect(nativeWorld,ids.slice(),values.slice(),force));}
    }
    @Override public synchronized void clampVelocities(int[] ids,float maxLinear,float maxAngular) {
      open();if(ids==null || ids.length>65536)throw new IllegalArgumentException("Invalid velocity guard batch");send(Operation.CLAMP,new float[]{maxLinear,maxAngular},ids.clone());
    }
    @Override public synchronized void clampVelocities(IntBuffer ids,float maxLinear,float maxAngular) {
      open();Objects.requireNonNull(ids);
      if(!ids.isDirect() || ids.order()!=ByteOrder.nativeOrder() || ids.remaining()>65536 || !Float.isFinite(maxLinear) || !Float.isFinite(maxAngular) || maxLinear<=0 || maxAngular<=0)
        throw new IllegalArgumentException("Velocity guard needs a direct native-order ID buffer and positive finite limits");
      if(ids.remaining()==0)return;
      if(settings.recordReplay()){int[] values=new int[ids.remaining()];ids.duplicate().get(values);send(Operation.CLAMP,new float[]{maxLinear,maxAngular},values);}
      else{nativeCalls++;checked(nClampDirect(nativeWorld,ids.slice(),maxLinear,maxAngular));}
    }
    @Override public synchronized void resetBodyForces(int b) { send(Operation.RESET,NO_FLOATS,b); }
    @Override public synchronized void setKinematic(int b,boolean e) { send(Operation.KINEMATIC,NO_FLOATS,b,e?1:0); }
    @Override public synchronized void setGravity(Vec3 g) { float[] c=new float[3];putVec(c,0,g);send(Operation.GRAVITY,c); }
    @Override public synchronized void setSoftBodyPin(int s,int v,Vec3 p) { float[] c=new float[3];putVec(c,0,p);send(Operation.PIN,c,s,v); }
    @Override public synchronized void setSoftBodyPins(int soft,IntBuffer ids,FloatBuffer positions) {
      open();int vertices=softCount(soft);Objects.requireNonNull(ids);Objects.requireNonNull(positions);
      if(!ids.isDirect() || !positions.isDirect() || ids.order()!=ByteOrder.nativeOrder() || positions.order()!=ByteOrder.nativeOrder() || ids.remaining()>vertices || positions.remaining()!=ids.remaining()*3)
        throw new IllegalArgumentException("Packed pins need matching direct native-order buffers");
      if(ids.remaining()==0)return;
      if(settings.recordReplay()) {
        int[] i=new int[ids.remaining()];float[] v=new float[positions.remaining()];ids.duplicate().get(i);positions.duplicate().get(v);execute(new PinsCommand(soft,i,v));
      }else{nativeCalls++;checked(nPinsDirect(nativeWorld,soft,ids.slice(),positions.slice()));}
    }
    @Override public synchronized void step() { send(Operation.STEP,NO_FLOATS);steps++; }
    @Override public synchronized long stepIndex() { open();return steps; }
    @Override public synchronized List<PhysicsSpec.BodyState> bodyStates() {
      open();int n=bodyCount;if(n==0)return List.of();float[] out=new float[Math.multiplyExact(n,13)];nativeCalls++;checked(nRead(nativeWorld,out));
      var r=new ArrayList<PhysicsSpec.BodyState>(n);
      for(int i=0;i<n;i++){int o=i*13;r.add(new PhysicsSpec.BodyState(new Pose(vec(out,o),Rotation.normalized(out[o+3],out[o+4],out[o+5],out[o+6])),vec(out,o+7),vec(out,o+10)));}
      return List.copyOf(r);
    }
    @Override public synchronized int readBodyStates(FloatBuffer output) {
      open();Objects.requireNonNull(output);
      int length=bodyCount*13;
      if(!output.isDirect() || output.isReadOnly() || output.order()!=ByteOrder.nativeOrder() || output.remaining()<length)
        throw new IllegalArgumentException("Native readback needs a writable direct native-order buffer with sufficient remaining capacity");
      if(length==0)return 0;
      var view=output.slice();view.limit(length);view=view.slice();
      nativeCalls++;checked(nReadDirect(nativeWorld,view));output.position(output.position()+length);return bodyCount;
    }
    @Override public synchronized Statistics statistics() { open();return new Statistics(nativeCalls,steps,historyBytes); }
    @Override public synchronized List<Vec3> softBodyVertices(int s) { return softRead(s,false); }
    @Override public synchronized List<Vec3> softBodyNormals(int s) { return softRead(s,true); }
    @Override public synchronized int readSoftBody(int soft,FloatBuffer output,boolean normals) {
      open();Objects.requireNonNull(output);int count=softCount(soft);int length=count*3;
      if(!output.isDirect() || output.isReadOnly() || output.order()!=ByteOrder.nativeOrder() || output.remaining()<length)
        throw new IllegalArgumentException("Native soft readback needs a writable direct native-order buffer");
      var view=output.slice();view.limit(length);nativeCalls++;checked(nSoftReadDirect(nativeWorld,soft,view.slice(),normals));output.position(output.position()+length);return count;
    }
    private int softCount(int s) {
      if(s<0 || s>=softVertexCounts.size())throw new IllegalArgumentException("Invalid soft-body ID");return softVertexCounts.get(s);
    }
    private List<Vec3> softRead(int s,boolean normals) {
      open();int n=softCount(s);float[] out=new float[Math.multiplyExact(n,3)];nativeCalls++;checked(nSoftRead(nativeWorld,s,out,normals));
      var r=new ArrayList<Vec3>(n);for(int i=0;i<n;i++)r.add(vec(out,i*3));return List.copyOf(r);
    }
    private record Checkpoint(Object owner,List<ReplayCommand> history,long stepIndex,long bytes,int bodies,int joints,List<Integer> softVertices) implements Snapshot {}
    @Override public synchronized Snapshot snapshot() {
      open();if(!settings.recordReplay())throw new IllegalStateException("Replay is not enabled for this live world");
      return new Checkpoint(identity,List.copyOf(history),steps,historyBytes,bodyCount,jointCount,List.copyOf(softVertexCounts));
    }
    @Override public synchronized void restore(Snapshot snapshot) {
      if(nativeWorld==0)throw new IllegalStateException("Physics world is closed");
      if(!(snapshot instanceof Checkpoint point)||point.owner!=identity)throw new IllegalArgumentException("Checkpoint belongs to another world");
      history.ensureCapacity(point.history.size());softVertexCounts.ensureCapacity(point.softVertices.size());
      long candidate=allocate(settings);
      try { for(var c:point.history){nativeCalls++;if(c.run(candidate)<0)throw new IllegalStateException("Native replay failed");} }
      catch(RuntimeException|Error failure){nDestroy(candidate);throw failure;}
      long old=nativeWorld;nativeWorld=candidate;history.clear();for(var command:point.history)history.add(command);historyBytes=point.bytes;steps=point.stepIndex;bodyCount=point.bodies;jointCount=point.joints;softVertexCounts.clear();for(var count:point.softVertices)softVertexCounts.add(count);failed=false;nDestroy(old);
    }
    @Override public synchronized void close() { if(nativeWorld!=0){long p=nativeWorld;nativeWorld=0;history.clear();softVertexCounts.clear();nDestroy(p);} }
  }

  private static float[] packBody(PhysicsSpec.Body b) {
    Objects.requireNonNull(b);
    return new float[]{b.shape().ordinal(),b.dimensions().x(),b.dimensions().y(),b.dimensions().z(),
      b.pose().position().x(),b.pose().position().y(),b.pose().position().z(),b.pose().rotation().x(),b.pose().rotation().y(),b.pose().rotation().z(),b.pose().rotation().w(),
      b.motion().ordinal(),b.mass(),b.friction(),b.restitution(),b.collisionGroup(),b.collisionMask(),b.linearDamping(),b.angularDamping(),b.margin()};
  }
  private static float[] packJoint(PhysicsSpec.Joint j) {
    Objects.requireNonNull(j);float[] c=new float[37];c[0]=j.type().ordinal();c[1]=j.bodyA();c[2]=j.bodyB();
    int o=putPose(c,3,j.frameA());o=putPose(c,o,j.frameB());o=putVec(c,o,j.linearLower());o=putVec(c,o,j.linearUpper());
    o=putVec(c,o,j.angularLower());o=putVec(c,o,j.angularUpper());o=putVec(c,o,j.linearStiffness());o=putVec(c,o,j.angularStiffness());
    c[o++]=j.springDamping();c[o]=j.disableLinkedCollision()?1:0;return c;
  }
  private record PinsCommand(int soft,int[] ids,float[] positions) implements ReplayCommand {
    public int run(long p){return nPins(p,soft,ids,positions);}
    public long bytes(){return 80L+4L*(ids.length+positions.length);}
  }
  private record BuildCommand(float[] bodies,float[] joints) implements ReplayCommand {
    public int run(long p) {return nBuild(p,bodies,joints);}
    public long bytes() {return 80L+4L*(bodies.length+joints.length);}
  }
  private record SoftCommand(float[] vertices,int[] indices,int[] pins,boolean rope,float mass,float[] cfg) implements ReplayCommand {
    public int run(long p) { return nSoft(p,vertices,indices,pins,rope,mass,cfg); }
    public long bytes() { return 80L+4L*(vertices.length+indices.length+pins.length+cfg.length); }
  }
  private static Vec3 vec(float[] a,int o) { return new Vec3(a[o],a[o+1],a[o+2]); }
  private static float[] vectors(Vec3 l,Vec3 a) { float[] out=new float[6];putVec(out,0,l);putVec(out,3,a);return out; }
  private static int putVec(float[] a,int o,Vec3 v) { Objects.requireNonNull(v);a[o++]=v.x();a[o++]=v.y();a[o++]=v.z();return o; }
  private static int putPose(float[] a,int o,Pose p) { Objects.requireNonNull(p);o=putVec(a,o,p.position());a[o++]=p.rotation().x();a[o++]=p.rotation().y();a[o++]=p.rotation().z();a[o++]=p.rotation().w();return o; }
  private static native int nAbiVersion();
  private static native long nFeatureBits();
  private static native int nScalarBits();
  private static native int nThreadCount();
  private static native int nKinematicFilter(long p,boolean enabled);
  private static native int nIterations(long p,int iterations);
  private static native long nCreate(float x,float y,float z,float step);
  private static native void nDestroy(long p);
  private static native int nPinsDirect(long p,int soft,IntBuffer ids,FloatBuffer positions);
  private static native int nPins(long p,int soft,int[] ids,float[] positions);
  private static native int nBody(long p,float[] c);
  private static native int nBuild(long p,float[] bodies,float[] joints);
  private static native int nImpulses(long p,int[] ids,float[] values);
  private static native int nForces(long p,int[] ids,float[] values);
  private static native int nLoadsDirect(long p,IntBuffer ids,FloatBuffer values,boolean force);
  private static native int nClampDirect(long p,IntBuffer ids,float linear,float angular);
  private static native int nClamp(long p,int[] ids,float linear,float angular);
  private static native int nReadDirect(long p,FloatBuffer output);
  private static native int nSoftReadDirect(long p,int id,FloatBuffer output,boolean normals);
  private static native int nPosesDirect(long p,IntBuffer ids,FloatBuffer poses);
  private static native int nJoint(long p,float[] c);
  private static native int nConfigureJoint(long p,int i,float[] c);
  private static native int nSoft(long p,float[] vertices,int[] indices,int[] pins,boolean rope,float mass,float[] config);
  private static native int nSoftAnchor(long p,int soft,int vertex,int body,boolean disableCollision);
  private static native int nPose(long p,int i,float[] pose);
  private static native int nPoses(long p,int[] ids,float[] poses);
  private static native int nVelocity(long p,int i,float[] v);
  private static native int nImpulse(long p,int i,float[] v,boolean local);
  private static native int nReset(long p,int i);
  private static native int nKinematic(long p,int i,boolean enabled);
  private static native int nGravity(long p,float[] gravity);
  private static native int nSoftPin(long p,int soft,int vertex,float[] position);
  private static native int nStep(long p);
  private static native int nCount(long p);
  private static native int nRead(long p,float[] out);
  private static native int nSoftCount(long p,int soft);
  private static native int nSoftRead(long p,int soft,float[] out,boolean normals);
}
