package cc.sirrus.ysmlib.scene.physics.wasm;

import cc.sirrus.ysmlib.scene.Pose;
import cc.sirrus.ysmlib.scene.Rotation;
import cc.sirrus.ysmlib.scene.Vec3;
import cc.sirrus.ysmlib.scene.physics.PhysicsProvider;
import cc.sirrus.ysmlib.scene.physics.PhysicsCapabilities;
import cc.sirrus.ysmlib.scene.physics.PhysicsSpec;
import cc.sirrus.ysmlib.scene.physics.PhysicsWorld;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Scalar Bullet 3.25 executed entirely by the JVM. No JNI, subprocess or native discovery. */
public final class WasmPhysicsProvider implements PhysicsProvider {
  @Override public String id() { return "bullet-3.25-chicory-jvm-v1"; }
  @Override public PhysicsCapabilities capabilities() { return new PhysicsCapabilities(id(), 4, 0xffL, false); }
  @Override public PhysicsWorld createWorld(PhysicsSpec.World settings) { return new World(settings); }

  private static final class Code {
    static final WasmModule MODULE = load();
    static final Function<Instance, Machine> FACTORY = MachineFactoryCompiler.compile(MODULE);
    private static WasmModule load() {
      try (var input = WasmPhysicsProvider.class.getResourceAsStream("/cc/sirrus/ysmlib/physics/bullet.wasm")) {
        if (input == null) throw new IOException("Bundled Bullet reactor is missing");
        return Parser.parse(input);
      } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
  }

  private static ByteBuffer buffer(int bytes) { return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN); }
  private static void vector(ByteBuffer b, Vec3 v) { b.putFloat(v.x()).putFloat(v.y()).putFloat(v.z()); }
  private static void pose(ByteBuffer b, Pose p) {
    vector(b,p.position()); var q=p.rotation(); b.putFloat(q.x()).putFloat(q.y()).putFloat(q.z()).putFloat(q.w());
  }
  private static Vec3 vector(ByteBuffer b) { return new Vec3(b.getFloat(),b.getFloat(),b.getFloat()); }

  private record Command(String function, byte[] data, int[] arguments, boolean appendAddress) {
    // Command data is created internally, never shared with or exposed to the caller.
    long bytes() { return 64L + data.length + (long) arguments.length * Integer.BYTES; }
  }

  private static final class Session implements AutoCloseable {
    final WasiPreview1 wasi;
    final Instance instance;
    private boolean closed;
    private int scratch;
    private int capacity;

    Session(PhysicsSpec.World settings) {
      // No preopened directories, environment or inherited streams. Simulation time comes from step().
      wasi=WasiPreview1.builder().build();
      try {
        instance=Instance.builder(Code.MODULE).withMachineFactory(Code.FACTORY)
            .withImportValues(ImportValues.builder().addFunction(wasi.toHostFunctions()).build()).build();
        if (call("ysm_physics_abi")!=4) throw new IllegalStateException("Bullet reactor ABI mismatch");
        var b=buffer(24);vector(b,settings.gravity());b.putFloat(settings.stepSeconds()).putFloat(settings.solverIterations()).putFloat(settings.kinematicFilter()?1:0);
        write(b.array()); checked("ysm_physics_create", scratch);
      } catch (RuntimeException | Error failure) {
        wasi.close(); throw failure;
      }
    }

    int call(String name,long... args) { return (int)instance.export(name).apply(args)[0]; }
    int checked(String name,long... args) {
      int result=call(name,args);
      if(result==-2) throw new IllegalStateException("Physics state became non-finite: "+name);
      if (result<0) throw new IllegalArgumentException("Physics operation rejected: "+name);
      return result;
    }
    void reserve(int size) {
      if (size<=capacity) return;
      if (size>64*1024*1024) throw new IllegalArgumentException("Physics transfer budget exceeded");
      int next=call("malloc",Math.max(1024,size));
      if (next==0) throw new IllegalStateException("Physics working memory exhausted");
      if (scratch!=0) instance.export("free").apply(scratch);
      scratch=next;capacity=Math.max(1024,size);
    }
    void write(byte[] data) { reserve(data.length);instance.memory().write(scratch,data); }
    int execute(Command c) {
      write(c.data);
      if (c.function.equals("ysm_physics_soft")) {
        // Offsets within a single bulk buffer: config, vertices, indices, pins.
        var a=c.arguments;
        return checked(c.function,scratch,scratch+a[0],a[1],scratch+a[2],a[3],a[4],scratch+a[5],a[6]);
      }
      long[] args=new long[c.arguments.length+(c.appendAddress?1:0)];
      for(int i=0;i<c.arguments.length;i++) args[i]=c.arguments[i];
      if(c.appendAddress) args[args.length-1]=scratch;
      // Impulse ABI places its local flag after the address.
      if(c.function.equals("ysm_physics_impulse")) args=new long[]{c.arguments[0],scratch,c.arguments[1]};
      return checked(c.function,args);
    }
    ByteBuffer read(String function,int count,int stride,int... prefix) {
      reserve(Math.multiplyExact(Math.multiplyExact(count,stride),4));
      long[] args=new long[prefix.length+1];
      for(int i=0;i<prefix.length;i++) args[i]=prefix[i];args[args.length-1]=scratch;
      checked(function,args);
      return ByteBuffer.wrap(instance.memory().readBytes(scratch,count*stride*4)).order(ByteOrder.LITTLE_ENDIAN);
    }
    @Override public void close() {
      if(closed) return;closed=true;
      try { instance.export("ysm_physics_destroy").apply(); }
      finally { wasi.close(); }
    }
  }

  private static final class World implements PhysicsWorld {
    private final PhysicsSpec.World settings;
    private final Object identity=new Object();
    private final ArrayList<Command> history=new ArrayList<>();
    private Session session;
    private long steps;
    private long historyBytes;
    private boolean failed;

    World(PhysicsSpec.World settings) { this.settings=Objects.requireNonNull(settings);session=new Session(settings); }
    private void open() { if(session==null || failed) throw new IllegalStateException("Physics world is closed or failed"); }
    private int execute(Command command) {
      open();
      // Bounded replay storage; the owner can replace a long-running session at an explicit reset.
      if(settings.recordReplay() && (historyBytes+command.bytes()>64L*1024*1024 || history.size()>=4_000_000))
        throw new IllegalStateException("Physics replay history budget exceeded");
      try {
        int result=session.execute(command);
        if(settings.recordReplay()) { history.add(command);historyBytes+=command.bytes(); }
        return result;
      } catch(IllegalArgumentException rejected) { throw rejected; }
      catch(RuntimeException failure) { failed=true;throw new IllegalStateException("Physics simulation failed; reset is required",failure); }
    }
    private int send(String function,ByteBuffer data,int... args) {
      return execute(new Command(function,data.array(),args,true));
    }
    private int simple(String function,int...args) {
      return execute(new Command(function,new byte[0],args,false));
    }

    @Override public synchronized int addBody(PhysicsSpec.Body body) {
      Objects.requireNonNull(body);var b=buffer(80);b.putFloat(body.shape().ordinal());vector(b,body.dimensions());pose(b,body.pose());
      b.putFloat(body.motion().ordinal()).putFloat(body.mass()).putFloat(body.linearDamping()).putFloat(body.angularDamping())
          .putFloat(body.restitution()).putFloat(body.friction()).putFloat(body.margin()).putFloat(body.collisionGroup()).putFloat(body.collisionMask());
      return send("ysm_physics_body",b);
    }
    @Override public synchronized int addJoint(PhysicsSpec.Joint joint) {
      Objects.requireNonNull(joint);var b=buffer(148);b.putFloat(joint.type().ordinal()).putFloat(joint.bodyA()).putFloat(joint.bodyB());
      pose(b,joint.frameA());pose(b,joint.frameB());vector(b,joint.linearLower());vector(b,joint.linearUpper());
      vector(b,joint.angularLower());vector(b,joint.angularUpper());vector(b,joint.linearStiffness());vector(b,joint.angularStiffness());
      b.putFloat(joint.springDamping()).putFloat(joint.disableLinkedCollision()?1:0);return send("ysm_physics_joint",b);
    }
    @Override public synchronized int addSoftBody(PhysicsSpec.SoftBody soft) {
      Objects.requireNonNull(soft);
      int vertexOffset=35*4;
      int indexOffset=Math.addExact(vertexOffset,Math.multiplyExact(soft.vertices().size(),12));
      int pinOffset=Math.addExact(indexOffset,Math.multiplyExact(soft.indices().size(),4));
      int size=Math.addExact(pinOffset,Math.multiplyExact(soft.pins().size(),4));
      if(size>64*1024*1024) throw new IllegalArgumentException("Soft-body transfer budget exceeded");
      var b=buffer(size);var c=soft.config();
      for(float v:new float[]{soft.mass(),soft.margin(),soft.collisionGroup(),soft.collisionMask(),
          c.velocityCorrection(),c.damping(),c.drag(),c.lift(),c.pressure(),c.volumeConservation(),c.friction(),c.poseMatching(),
          c.rigidHardness(),c.kineticHardness(),c.softHardness(),c.anchorHardness(),c.softRigidHardness(),c.softKineticHardness(),
          c.softSoftHardness(),c.softRigidSplit(),c.softKineticSplit(),c.softSoftSplit(),c.velocityIterations(),c.positionIterations(),
          c.driftIterations(),c.clusterIterations(),c.linearStiffness(),c.angularStiffness(),c.volumeStiffness(),c.aeroModel(),
          c.collisionFlags(),c.clusterCount(),c.bendingDistance(),c.clustersEnabled()?1:0,c.randomizeConstraints()?1:0}) b.putFloat(v);
      for(var v:soft.vertices()) vector(b,v);
      for(int i:soft.indices()) b.putInt(i);for(int i:soft.pins()) b.putInt(i);
      return execute(new Command("ysm_physics_soft",b.array(),new int[]{vertexOffset,soft.vertices().size(),indexOffset,
          soft.indices().size(),soft.rope()?1:0,pinOffset,soft.pins().size()},false));
    }
    @Override public synchronized void anchorSoftBody(int soft,int vertex,int rigid,boolean disableCollision) {
      simple("ysm_physics_anchor",soft,vertex,rigid,disableCollision?1:0);
    }
    @Override public synchronized void setBodyPose(int body,Pose pose) {
      var b=buffer(28);pose(b,Objects.requireNonNull(pose));send("ysm_physics_set_pose",b,body);
    }
    @Override public synchronized void setVelocity(int body,Vec3 linear,Vec3 angular) {
      var b=buffer(24);vector(b,linear);vector(b,angular);send("ysm_physics_velocity",b,body);
    }
    @Override public synchronized void applyImpulse(int body,Vec3 linear,Vec3 angular,boolean local) {
      var b=buffer(24);vector(b,linear);vector(b,angular);send("ysm_physics_impulse",b,body,local?1:0);
    }
    @Override public synchronized void configureJoint(int joint,PhysicsSpec.JointOptions options) {
      var b=buffer(60);
      if(options instanceof PhysicsSpec.SixDofOptions c) {
        b.putFloat(0);vector(b,c.linearStopErp());vector(b,c.angularStopErp());vector(b,c.linearStopCfm());vector(b,c.angularStopCfm());
      } else if(options instanceof PhysicsSpec.ConeTwistOptions c) {
        for(float v:new float[]{3,c.swing1(),c.swing2(),c.twist(),c.softness(),c.bias(),c.relaxation(),c.damping(),c.fixThreshold(),c.motorEnabled()?1:0,c.maxMotorImpulse(),
            c.motorTarget().x(),c.motorTarget().y(),c.motorTarget().z(),c.motorTarget().w()}) b.putFloat(v);
      } else if(options instanceof PhysicsSpec.SliderOptions c) {
        for(float v:new float[]{4,c.linearEnabled()?1:0,c.linearVelocity(),c.linearForce(),c.angularEnabled()?1:0,c.angularVelocity(),c.angularForce()}) b.putFloat(v);
      } else if(options instanceof PhysicsSpec.HingeOptions c) {
        for(float v:new float[]{5,c.softness(),c.bias(),c.relaxation(),c.motorEnabled()?1:0,c.velocity(),c.maxImpulse()}) b.putFloat(v);
      } else throw new IllegalArgumentException("Unknown joint options");
      send("ysm_physics_configure_joint",b,joint);
    }
    @Override public synchronized void applyForces(List<PhysicsSpec.Force> forces) {
      if(forces.size()>65536)throw new IllegalArgumentException("Force batch budget exceeded");var b=buffer(forces.size()*32);
      for(var force:forces){b.putFloat(force.body());vector(b,force.linear());vector(b,force.angular());b.putFloat(force.local()?1:0);}send("ysm_physics_forces",b,forces.size());
    }
    @Override public synchronized void clampVelocities(int[] ids,float maxLinear,float maxAngular) {
      if(ids==null || ids.length>65536)throw new IllegalArgumentException("Invalid velocity guard batch");
      var b=buffer((ids.length+2)*4);b.putFloat(maxLinear).putFloat(maxAngular);for(int id:ids)b.putFloat(id);send("ysm_physics_clamp_velocities",b,ids.length);
    }
    @Override public synchronized void resetBodyForces(int body) { simple("ysm_physics_reset_forces",body); }
    @Override public synchronized void setKinematic(int body,boolean enabled) { simple("ysm_physics_set_kinematic",body,enabled?1:0); }
    @Override public synchronized void setGravity(Vec3 gravity) { var b=buffer(12);vector(b,Objects.requireNonNull(gravity));send("ysm_physics_set_gravity",b); }
    @Override public synchronized void setSoftBodyPin(int soft,int vertex,Vec3 position) {
      var b=buffer(12);vector(b,position);send("ysm_physics_pin",b,soft,vertex);
    }
    @Override public synchronized void step() { simple("ysm_physics_step");steps++; }
    @Override public synchronized long stepIndex() { open();return steps; }
    @Override public synchronized List<PhysicsSpec.BodyState> bodyStates() {
      open();int count=session.checked("ysm_physics_body_count");var b=session.read("ysm_physics_read_bodies",count,13);
      var states=new ArrayList<PhysicsSpec.BodyState>(count);
      for(int i=0;i<count;i++) {
        var position=vector(b);var rotation=Rotation.normalized(b.getFloat(),b.getFloat(),b.getFloat(),b.getFloat());
        states.add(new PhysicsSpec.BodyState(new Pose(position,rotation),vector(b),vector(b)));
      }
      return List.copyOf(states);
    }
    /** Transfer solver scalars directly, matching the native packed ABI. The MMD binding performs
     * its one explicit normalization when constructing the evaluated pose, just like bodyStates(). */
    @Override public synchronized int readBodyStates(java.nio.FloatBuffer output) {
      open();Objects.requireNonNull(output);int count=session.checked("ysm_physics_body_count");int length=Math.multiplyExact(count,13);
      if(output.isReadOnly() || output.remaining()<length)throw new IllegalArgumentException("Insufficient body state buffer");
      var data=session.read("ysm_physics_read_bodies",count,13).asFloatBuffer();
      for(int i=0;i<length;i++)if(!Float.isFinite(data.get(i))){failed=true;throw new IllegalStateException("Non-finite packed physics state");}
      output.put(data);return count;
    }
    @Override public synchronized List<Vec3> softBodyVertices(int soft) {
      open();int count=session.checked("ysm_physics_soft_count",soft);var b=session.read("ysm_physics_read_soft",count,3,soft);
      var vertices=new ArrayList<Vec3>(count);for(int i=0;i<count;i++) vertices.add(vector(b));return List.copyOf(vertices);
    }
    @Override public synchronized List<Vec3> softBodyNormals(int soft) {
      open();int count=session.checked("ysm_physics_soft_count",soft);var b=session.read("ysm_physics_read_soft_normals",count,3,soft);
      var normals=new ArrayList<Vec3>(count);for(int i=0;i<count;i++) normals.add(vector(b));return List.copyOf(normals);
    }
    private record Checkpoint(Object owner,List<Command> history,long stepIndex,long bytes) implements Snapshot {}
    @Override public synchronized Snapshot snapshot() {
      open();if(!settings.recordReplay()) throw new IllegalStateException("Replay is not enabled for this live world");
      return new Checkpoint(identity,List.copyOf(history),steps,historyBytes);
    }
    @Override public synchronized void restore(Snapshot snapshot) {
      if(session==null) throw new IllegalStateException("Physics world is closed");
      if(!(snapshot instanceof Checkpoint point) || point.owner!=identity)
        throw new IllegalArgumentException("Checkpoint belongs to another world");
      // Replay into an isolated candidate. A failed restore does not mutate the existing world.
      var next=new Session(settings);
      try { for(var command:point.history) next.execute(command); }
      catch(RuntimeException | Error failure) { next.close();throw failure; }
      var old=session;session=next;history.clear();history.addAll(point.history);steps=point.stepIndex;historyBytes=point.bytes;failed=false;
      old.close();
    }
    @Override public synchronized void close() {
      if(session==null) return;var old=session;session=null;history.clear();old.close();
    }
  }
}
