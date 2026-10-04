package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.Deformer;
import cc.sirrus.ysmlib.scene.physics.*;
import java.util.*;
import java.nio.*;

/** PMX rigid/soft binding in original model units. The generic solver has no knowledge of MMD bones. */
final class MmdPhysicsBinding implements AutoCloseable {
  private static final Vec3 X=new Vec3(1,0,0),Y=new Vec3(0,1,0),Z=new Vec3(0,0,1);
  private record Soft(int id,int[] vertices,int[] pins,IntBuffer pinIds,FloatBuffer targets,FloatBuffer positions,FloatBuffer normals) {}
  private final PmxDocument source;
  private final PhysicsWorld world;
  private final int[] bodies;
  private final int[] drivers;
  private final boolean[] kinematic;
  private final Pose[] offsets;
  private final List<Soft> soft=new ArrayList<>();
  private final Set<String> warningKeys=new LinkedHashSet<>();
  private final MeshAsset mesh;
  private final List<CompatibilityReport.Diagnostic> diagnostics;
  private final boolean packed,hasPhysicsWithBone;
  private final IntBuffer poseIds,impulseIds;
  private final FloatBuffer poseValues,impulseValues,bodyValues;
  private final boolean[] resetBodies;
  private final DeformationProvider.Session deformation;
  private final Vec3 sourceGravity;
  private PhysicsEnvironment environment;
  private boolean environmentDirty,resetHost;
  void environment(PhysicsEnvironment input){environment=input;environmentDirty=true;resetHost|=input!=null&&input.reset();}

  MmdPhysicsBinding(PmxDocument source,MeshAsset mesh,MmdRig rig,PhysicsProvider provider,MmdPlayback.Settings settings,
                    List<CompatibilityReport.Diagnostic> diagnostics,boolean packed,DeformationProvider.Session deformation) {
    this.deformation=deformation;
    sourceGravity=settings.gravity();
    this.packed=packed;hasPhysicsWithBone=source.rigidBodies().stream().anyMatch(b->b.mode()==2);
    if(source.rigidBodies().size()>65536)throw new IllegalArgumentException("MMD rigid body budget exceeded");
    this.diagnostics=Objects.requireNonNull(diagnostics);
    this.source=source;this.mesh=mesh;bodies=new int[source.rigidBodies().size()];offsets=new Pose[bodies.length];drivers=new int[bodies.length];kinematic=new boolean[bodies.length];
    poseIds=packed?ints(bodies.length):null;poseValues=packed?floats(bodies.length*7):null;bodyValues=packed?floats(bodies.length*13):null;
    int impulseCount=(int)Math.min(65536,source.morphs().stream().filter(m->m.type()==10).mapToLong(m->m.offsets().size()).sum());
    impulseIds=packed?ints(impulseCount):null;impulseValues=packed?floats(impulseCount*7):null;resetBodies=new boolean[bodies.length];
    world=provider.createWorld(new PhysicsSpec.World(settings.gravity(),1f/settings.frequencyHz(),settings.solverIterations(),false,settings.kinematicFilter()));
    try {
      var initial=new Pose[bodies.length];
      var bodySpecs=new ArrayList<PhysicsSpec.Body>(bodies.length);
      for(int i=0;i<bodies.length;i++) {
        var b=source.rigidBodies().get(i);var origin=new Pose(b.position(),eulerYXZ(b.euler()));
        int driver=drivers[i]=b.bone()<0?(source.bones().isEmpty()?-1:0):b.bone();
        offsets[i]=driver<0?origin:new Pose(source.bones().get(driver).position().multiply(-1),Rotation.IDENTITY).multiply(origin);
        initial[i]=driver<0?origin:rig.world[driver].multiply(offsets[i]);
        var motion=b.mode()==0?PhysicsSpec.Motion.KINEMATIC:b.mass()==0?PhysicsSpec.Motion.STATIC:PhysicsSpec.Motion.DYNAMIC;
        var spec=new PhysicsSpec.Body(PhysicsSpec.Shape.values()[b.shape()],b.size(),initial[i],motion,
            motion==PhysicsSpec.Motion.DYNAMIC?b.mass():0,unit(b.linearDamping(),"rigidBody["+i+"].linearDamping"),
            unit(b.angularDamping(),"rigidBody["+i+"].angularDamping"),unit(b.restitution(),"rigidBody["+i+"].restitution"),
            nonnegative(b.friction(),"rigidBody["+i+"].friction"),.04f,1<<b.group(),b.collisionMask());
        bodySpecs.add(spec);bodies[i]=packed?i:world.addBody(spec);
      }
      var jointSpecs=new ArrayList<PhysicsSpec.Joint>();
      if(settings.jointsEnabled()) for(var j:source.joints()) {
        // Constraints are authored in bind space even if playback begins at a non-identity animation pose.
        var joint=new Pose(j.position(),eulerZYX(j.euler()));
        var a=j.bodyA()<0?Pose.IDENTITY:new Pose(source.rigidBodies().get(j.bodyA()).position(),eulerYXZ(source.rigidBodies().get(j.bodyA()).euler()));
        var b=j.bodyB()<0?Pose.IDENTITY:new Pose(source.rigidBodies().get(j.bodyB()).position(),eulerYXZ(source.rigidBodies().get(j.bodyB()).euler()));
        jointSpecs.add(new PhysicsSpec.Joint(PhysicsSpec.JointType.values()[j.type()],j.bodyA()<0?-1:bodies[j.bodyA()],j.bodyB()<0?-1:bodies[j.bodyB()],
            a.inverse().multiply(joint),b.inverse().multiply(joint),j.linearLower(),j.linearUpper(),j.angularLower(),j.angularUpper(),j.linearSpring(),j.angularSpring(),.5f,settings.disableLinkedCollision()));
      }
      var jointIds=new ArrayList<Integer>();
      if(packed){
        var structure=world.addStructure(bodySpecs,jointSpecs);
        for(int i=0;i<bodies.length;i++) { bodies[i]=structure.bodies().get(i);if(bodies[i]!=i)throw new IllegalStateException("Packed MMD needs indexed body IDs in a fresh world"); }
        jointIds.addAll(structure.joints());
      }else for(var joint:jointSpecs)jointIds.add(world.addJoint(joint));
      for(int i=0;i<bodies.length;i++) {
        int driver=drivers[i];if(bodySpecs.get(i).motion()==PhysicsSpec.Motion.DYNAMIC && driver>=0 && !rig.physicsEnabled[driver]) {world.setKinematic(bodies[i],true);kinematic[i]=true;}
      }
      if(settings.jointsEnabled()) for(int i=0;i<source.joints().size();i++) {
        var j=source.joints().get(i);int id=jointIds.get(i);
        if(j.type()==3) world.configureJoint(id,new PhysicsSpec.ConeTwistOptions(j.angularLower().z(),j.angularLower().y(),j.angularLower().x(),
            j.linearSpring().x(),j.linearSpring().y(),j.linearSpring().z(),j.linearLower().x(),j.linearUpper().x(),j.linearUpper().y()>0,j.linearUpper().z(),eulerZYX(j.angularSpring())));
        else if(j.type()==4) world.configureJoint(id,new PhysicsSpec.SliderOptions(j.linearSpring().x()>0,j.linearSpring().y(),j.linearSpring().z(),j.angularSpring().x()>0,j.angularSpring().y(),j.angularSpring().z()));
        else if(j.type()==5) world.configureJoint(id,new PhysicsSpec.HingeOptions(j.linearSpring().x(),j.linearSpring().y(),j.linearSpring().z(),j.angularSpring().x()>0,j.angularSpring().y(),j.angularSpring().z()));
      }
      for(var s:source.softBodies()) addSoft(s,rig);
    } catch(RuntimeException|Error failure) { world.close();throw failure; }
  }

  /** PMX tools in the wild sometimes emit out-of-range unit coefficients. Keep the source
   * value in the diagnostic, then pass a finite value accepted by the generic physics API. */
  private float unit(float value,String location) {
    if(Float.isFinite(value) && value>=0 && value<=1) return value;
    float repaired=Float.isNaN(value)?0f:value<0?0f:value>1?1f:0f;
    addWarning(location,value,repaired,"Physics unit value "+value+" is outside [0,1]; clamped to "+repaired);
    return repaired;
  }
  private float nonnegative(float value,String location) {
    if(Float.isFinite(value) && value>=0) return value;
    float repaired=0f;
    addWarning(location,value,repaired,"Physics non-negative value "+value+" is invalid; repaired to "+repaired);
    return repaired;
  }
  private void addWarning(String location,float value,float repaired,String message) {
    // Hundreds of rigid bodies often repeat the same malformed coefficient. Keep one
    // diagnostic per field/value pair so pose publication and the UI remain cheap.
    int dot=location.indexOf('.');String field=dot<0?location:location.substring(dot+1);
    if(warningKeys.add(field+"|"+Float.floatToIntBits(value)))
      diagnostics.add(new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.WARNING,
          location.replaceFirst("rigidBody\\[\\d+\\]","rigidBody[*]"),message));
  }

  private void addSoft(PmxDocument.SoftBody s,MmdRig rig) {
    for(var anchor:s.anchors()) if(anchor.near()) throw new UnsupportedOperationException("PMX soft anchor near mode has not yet been verified");
    var primitive=mesh.primitives().get(s.material());var indices=primitive.indices();
    var remap=new LinkedHashMap<Integer,Integer>();for(int i=0;i<indices.size();i++) remap.computeIfAbsent(indices.get(i),ignored->remap.size());
    var global=remap.keySet().stream().mapToInt(i->i).toArray();var vertices=new ArrayList<Vec3>();
    var animated=deform(rig.pose()).get("POSITION").values();for(int i:global) vertices.add(vec(animated,i));
    var topology=new ArrayList<Integer>();
    if(s.shape()==0) for(int i=0;i<indices.size();i++) topology.add(remap.get(indices.get(i)));
    else {
      // PMX rope order follows material vertex occurrence, not the complete model's vertex range.
      for(int i=1;i<global.length;i++) { topology.add(i-1);topology.add(i); }
    }
    var pins=new ArrayList<Integer>();for(int i=0;i<s.pins().size();i++) pins.add(softVertex(remap,s.pins().get(i)));
    var c=s.config();var k=s.cluster();var iterations=s.iterations();var stiffness=s.stiffness();boolean clusters=(s.flags()&2)!=0;
    var config=new PhysicsSpec.SoftConfig(c.get(0),c.get(1),c.get(2),c.get(3),c.get(4),c.get(5),c.get(6),c.get(7),c.get(8),c.get(9),c.get(10),c.get(11),
        k.get(0),k.get(1),k.get(2),k.get(3),k.get(4),k.get(5),iterations.get(0),iterations.get(1),iterations.get(2),iterations.get(3),
        stiffness.get(0),stiffness.get(1),stiffness.get(2),switch(s.aeroModel()) { case 0->0;case 1->1;case 2->3;case 3->4;case 4->6;default->throw new IllegalArgumentException("Invalid PMX aero model"); },
        clusters?0x22:0x11,s.clusterCount(),(s.flags()&1)!=0?s.bendingDistance():0,clusters,(s.flags()&4)!=0);
    int id=world.addSoftBody(new PhysicsSpec.SoftBody(vertices,topology,s.shape()==1,pins,s.mass(),s.margin(),1<<s.group(),s.collisionMask(),config));
    for(var anchor:s.anchors()) world.anchorSoftBody(id,softVertex(remap,anchor.vertex()),bodies[anchor.body()],false);
    var pinned=pins.stream().mapToInt(i->i).toArray();var pinIds=packed?ints(pinned.length):null;
    if(packed)for(int i=0;i<pinned.length;i++)pinIds.put(i,pinned[i]);
    soft.add(new Soft(id,global,pinned,pinIds,packed?floats(pinned.length*3):null,packed?floats(global.length*3):null,packed?floats(global.length*3):null));
  }
  private static int softVertex(Map<Integer,Integer> remap,int source) {
    var local=remap.get(source);if(local==null) throw new IllegalArgumentException("Soft anchor/pin vertex is outside its material");return local;
  }

  void step(MmdRig rig) {
    if(environmentDirty){world.environment(environment);if(environment==null)world.setGravity(sourceGravity);environmentDirty=false;}
    if(resetHost) {
      // Teleports/stalls discard momentum without resetting authored constraints or IDs.
      for(int i=0;i<bodies.length;i++) {
        int driver=drivers[i];if(driver>=0)world.setBodyPose(bodies[i],rig.world[driver].multiply(offsets[i]));
        world.resetBodyForces(bodies[i]);
      }
      resetHost=false;
    }
    var states=packed?null:world.bodyStates();
    if(packed) {
      poseIds.clear();poseValues.clear();
      if(hasPhysicsWithBone)readBodies();
    }
    for(int i=0;i<bodies.length;i++) {
      var b=source.rigidBodies().get(i);int driver=drivers[i];if(driver<0) continue;var animated=rig.world[driver].multiply(offsets[i]);
      boolean disabled=!rig.physicsEnabled[driver];
      if(b.mode()!=0 && b.mass()>0 && disabled!=kinematic[i]) { world.setKinematic(bodies[i],disabled);kinematic[i]=disabled; }
      if(b.mode()==0 || disabled) syncPose(bodies[i],animated);
      else if(b.mode()==2) {
        // Follow animated translation while preserving the body's solver-owned orientation and velocities.
        var dynamic=packed?bodyPose(bodies[i]):states.get(bodies[i]).pose();var bone=dynamic.multiply(offsets[i].inverse());
        var adjusted=new Pose(rig.world[driver].position(),bone.rotation()).multiply(offsets[i]);
        syncPose(bodies[i],adjusted);
      }
    }
    if(packed && poseIds.position()>0){poseIds.flip();poseValues.flip();world.setBodyPoses(poseIds,poseValues);}
    var morphs=rig.pose().morphs();Arrays.fill(resetBodies,false);for(var impulse:morphs.impulses())if(impulse.reset())resetBodies[impulse.body()]=true;
    for(int body=0;body<resetBodies.length;body++)if(resetBodies[body])world.resetBodyForces(bodies[body]);
    if(packed) {
      impulseIds.clear();impulseValues.clear();
      for(var impulse:morphs.impulses())if(!resetBodies[impulse.body()]) {
        if(!impulseIds.hasRemaining())flushImpulses();
        impulseIds.put(bodies[impulse.body()]);put(impulseValues,impulse.velocity());put(impulseValues,impulse.torque());impulseValues.put(impulse.local()?1:0);
      }
      flushImpulses();
    } else for(var impulse:morphs.impulses()) if(!resetBodies[impulse.body()]) world.applyImpulse(bodies[impulse.body()],impulse.velocity(),impulse.torque(),impulse.local());
    if(!soft.isEmpty()) {
      var positions=deform(rig.pose()).get("POSITION").values();
      for(var body:soft) {
        if(packed){body.targets.clear();for(int pin:body.pins)put(body.targets,vec(positions,body.vertices[pin]));body.targets.flip();if(body.pins.length>0)world.setSoftBodyPins(body.id,body.pinIds,body.targets);}
        else for(int pin:body.pins) world.setSoftBodyPin(body.id,pin,vec(positions,body.vertices[pin]));
      }
    }
    world.step();reflect(rig);
  }
  private Map<String,MeshAsset.Attribute> deform(MmdPose pose) {
    return deformation.deform(pose.palette(),pose.morphs().meshWeights(),SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION);
  }

  void reflect(MmdRig rig) {
    var states=packed?null:world.bodyStates();if(packed)readBodies();var overrides=new LinkedHashMap<Integer,Pose>();
    for(int i=0;i<bodies.length;i++) {
      var b=source.rigidBodies().get(i);if(b.mode()==0 || b.bone()<0 || !rig.physicsEnabled[b.bone()]) continue;
      var value=(packed?bodyPose(bodies[i]):states.get(bodies[i]).pose()).multiply(offsets[i].inverse());
      if(b.mode()==2) value=new Pose(rig.world[b.bone()].position(),value.rotation());
      overrides.put(b.bone(),value);
    }
    rig.applyPhysics(overrides);
  }

  List<Map<String,MeshAsset.Attribute>> geometry(MmdPose pose) {
    if(mesh.primitives().isEmpty()) return List.of();
    // MMD material primitives share a single full vertex table and identical morph/skin data.
    var attributes=new LinkedHashMap<>(deformation.deform(pose.palette(),pose.morphs().meshWeights(),SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION));
    if(!soft.isEmpty()) {
      float[] positions=attributes.get("POSITION").values().copy(),normals=attributes.get("NORMAL").values().copy();
      for(var body:soft) {
        var p=packed?null:world.softBodyVertices(body.id);var n=packed?null:world.softBodyNormals(body.id);
        if(packed){body.positions.clear();body.normals.clear();world.readSoftBody(body.id,body.positions,false);world.readSoftBody(body.id,body.normals,true);}
        for(int i=0;i<body.vertices.length;i++) {
          var position=packed?vec(body.positions,i):p.get(i);var normal=packed?vec(body.normals,i):n.get(i);
          put(positions,body.vertices[i],position);if(normal.dot(normal)>1e-15)put(normals,body.vertices[i],normal.normalized());
        }
      }
      attributes.put("POSITION",new MeshAsset.Attribute(3,new FloatData(positions)));attributes.put("NORMAL",new MeshAsset.Attribute(3,new FloatData(normals)));
    }
    return Collections.nCopies(mesh.primitives().size(),Map.copyOf(attributes));
  }
  private static IntBuffer ints(int count){return ByteBuffer.allocateDirect(Math.multiplyExact(count,4)).order(ByteOrder.nativeOrder()).asIntBuffer();}
  private static FloatBuffer floats(int count){return ByteBuffer.allocateDirect(Math.multiplyExact(count,4)).order(ByteOrder.nativeOrder()).asFloatBuffer();}
  private static void put(FloatBuffer buffer,Vec3 v){buffer.put(v.x()).put(v.y()).put(v.z());}
  private static Vec3 vec(FloatBuffer buffer,int vertex){int i=vertex*3;return new Vec3(buffer.get(i),buffer.get(i+1),buffer.get(i+2));}
  private void readBodies(){bodyValues.clear();if(bodies.length>0)world.readBodyStates(bodyValues);}
  private Pose bodyPose(int id){int i=id*13;return new Pose(new Vec3(bodyValues.get(i),bodyValues.get(i+1),bodyValues.get(i+2)),Rotation.normalized(bodyValues.get(i+3),bodyValues.get(i+4),bodyValues.get(i+5),bodyValues.get(i+6)));}
  private void syncPose(int id,Pose pose) {
    if(!packed){world.setBodyPose(id,pose);return;}
    poseIds.put(id);put(poseValues,pose.position());var q=pose.rotation();poseValues.put(q.x()).put(q.y()).put(q.z()).put(q.w());
  }
  private void flushImpulses() {
    if(impulseIds.position()==0)return;
    impulseIds.flip();impulseValues.flip();world.applyImpulses(impulseIds,impulseValues);impulseIds.clear();impulseValues.clear();
  }
  static Rotation eulerYXZ(Vec3 e) { return Rotation.axisAngle(Y,e.y()).multiply(Rotation.axisAngle(X,e.x())).multiply(Rotation.axisAngle(Z,e.z())); }
  static Rotation eulerZYX(Vec3 e) { return Rotation.axisAngle(Z,e.z()).multiply(Rotation.axisAngle(Y,e.y())).multiply(Rotation.axisAngle(X,e.x())); }
  private static Vec3 vec(FloatData data,int i) { return new Vec3(data.get(i*3),data.get(i*3+1),data.get(i*3+2)); }
  private static void put(float[] data,int i,Vec3 v) { data[i*3]=v.x();data[i*3+1]=v.y();data[i*3+2]=v.z(); }
  @Override public void close() { world.close(); }
}
