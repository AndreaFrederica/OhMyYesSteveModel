package cc.sirrus.ysmlib.scene.physics.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.physics.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class NativePhysicsBatchTest {
  @Test void finiteImpulseAndAccumulatedForceOverflowQuarantineWorldAndRestoreCleanSnapshot() {
    var provider=NativePhysicsConformanceTest.nativeProvider();
    try(var world=provider.createWorld(PhysicsSpec.World.defaults())) {
      world.addBody(body(0));var snapshot=world.snapshot();var before=world.bodyStates();
      assertThrows(IllegalStateException.class,()->world.applyImpulse(0,Vec3.ZERO,new Vec3(Float.MAX_VALUE,0,0),false));
      assertThrows(IllegalStateException.class,world::step);assertThrows(IllegalStateException.class,world::bodyStates);
      world.restore(snapshot);assertEquals(before,world.bodyStates());
      var forces=List.of(new PhysicsSpec.Force(0,new Vec3(Float.MAX_VALUE,0,0),Vec3.ZERO,false));world.applyForces(forces);
      assertThrows(IllegalStateException.class,()->world.applyForces(forces));assertThrows(IllegalStateException.class,world::step);
      world.restore(snapshot);world.step();assertEquals(1,world.stepIndex());
    }
  }
  @Test void unalignedDirectViewsAreRejectedBeforeMutationOrPublication() {
    try(var world=NativePhysicsConformanceTest.nativeProvider().createWorld(PhysicsSpec.World.defaults().withoutReplay())) {
      world.addBody(body(0));var before=world.bodyStates();
      var backing=ByteBuffer.allocateDirect(53);backing.position(1);
      var unaligned=backing.slice().order(ByteOrder.nativeOrder()).asFloatBuffer();
      unaligned.put(6,1).limit(7);var ids=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
      assertThrows(IllegalArgumentException.class,()->world.setBodyPoses(ids,unaligned));
      assertThrows(IllegalArgumentException.class,()->world.applyForces(ids,unaligned));
      assertEquals(before,world.bodyStates());unaligned.limit(13);unaligned.put(0,123);
      assertThrows(IllegalArgumentException.class,()->world.readBodyStates(unaligned));assertEquals(123,unaligned.get(0));assertEquals(0,unaligned.position());
    }
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
  void reusablePackedForcesAndImpulsesPreserveViewsRejectAtomicallyAndReplay(boolean replay) {
    var provider=NativePhysicsConformanceTest.nativeProvider();
    var settings=new PhysicsSpec.World(Vec3.ZERO,1f/60,10,replay);
    try(var batch=provider.createWorld(settings);var scalar=provider.createWorld(settings)) {
      for(var world:List.of(batch,scalar))for(int i=0;i<8;i++)world.addBody(body(i));
      for(var world:List.of(batch,scalar))world.setBodyPose(2,new Pose(new Vec3(2,3,0),Rotation.axisAngle(new Vec3(0,1,0),.7)));
      var ids=ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder()).asIntBuffer();ids.position(1).limit(9);
      var loads=buffer(70);loads.position(7).limit(63);
      var forces=new ArrayList<PhysicsSpec.Force>();var impulses=new ArrayList<PhysicsSpec.Impulse>();
      for(int i=0;i<8;i++) {
        ids.put(i+1,i);int offset=7+i*7;
        var linear=new Vec3(.02f*i,.2f,-.03f*i);var angular=new Vec3(.01f,.02f*i,-.01f);
        loads.put(offset,linear.x()).put(offset+1,linear.y()).put(offset+2,linear.z()).put(offset+3,angular.x()).put(offset+4,angular.y()).put(offset+5,angular.z()).put(offset+6,i%2);
        forces.add(new PhysicsSpec.Force(i,linear,angular,i%2==1));impulses.add(new PhysicsSpec.Impulse(i,linear,angular,i%2==1));
      }
      var snapshot=replay?batch.snapshot():null;
      int[] guards={0,1,2,3,4,5,6,7};
      long calls=batch.statistics().nativeCalls();batch.applyImpulses(ids,loads);assertEquals(calls+1,batch.statistics().nativeCalls());scalar.applyImpulses(impulses);
      for(int step=0;step<30;step++) {batch.applyForces(ids,loads);scalar.applyForces(forces);batch.step();scalar.step();batch.clampVelocities(ids,.2f,.05f);scalar.clampVelocities(guards,.2f,.05f);assertEquals(scalar.bodyStates(),batch.bodyStates());}
      assertEquals(1,ids.position());assertEquals(7,loads.position());assertEquals(9,ids.limit());assertEquals(63,loads.limit());
      var after=batch.bodyStates();loads.put(62,2);assertThrows(IllegalArgumentException.class,()->batch.applyImpulses(ids,loads));assertEquals(after,batch.bodyStates());loads.put(62,1);
      ids.put(8,99);assertThrows(IllegalArgumentException.class,()->batch.applyForces(ids,loads));assertThrows(IllegalArgumentException.class,()->batch.clampVelocities(ids,.001f,.001f));assertEquals(after,batch.bodyStates());ids.put(8,7);
      // A rejected late entry must not leave forces on any earlier body.
      batch.step();scalar.step();assertEquals(scalar.bodyStates(),batch.bodyStates());
      if(replay){batch.restore(snapshot);batch.applyImpulses(ids,loads);for(int step=0;step<30;step++){batch.applyForces(ids,loads);batch.step();batch.clampVelocities(ids,.2f,.05f);}assertEquals(after,batch.bodyStates());}
    }
  }
  static PhysicsSpec.Body body(int i) {
    return new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(.25f,0,0),new Pose(new Vec3(i,3,0),Rotation.IDENTITY),PhysicsSpec.Motion.DYNAMIC,1,0,0,0,.5f,.04f,1,0);
  }
  static PhysicsSpec.Joint joint(int a,int b) {
    return new PhysicsSpec.Joint(PhysicsSpec.JointType.SPRING_6DOF,a,b,Pose.IDENTITY,Pose.IDENTITY,new Vec3(-1,-1,-1),new Vec3(1,1,1),new Vec3(-.5f,-.5f,-.5f),new Vec3(.5f,.5f,.5f),new Vec3(10,10,10),Vec3.ZERO,.5f,true);
  }
  static FloatBuffer buffer(int floats) {return ByteBuffer.allocateDirect(floats*4).order(ByteOrder.nativeOrder()).asFloatBuffer();}

  @ParameterizedTest @ValueSource(ints={32,128,256})
  void packedStructureImpulsesDirectReadbackAndReplayMatchScalarCalls(int count) {
    var p=NativePhysicsConformanceTest.nativeProvider();
    try(var batch=p.createWorld(PhysicsSpec.World.defaults());var scalar=p.createWorld(PhysicsSpec.World.defaults())) {
      var bodies=new ArrayList<PhysicsSpec.Body>();var joints=new ArrayList<PhysicsSpec.Joint>();var impulses=new ArrayList<PhysicsSpec.Impulse>();
      for(int i=0;i<count;i++){bodies.add(body(i));impulses.add(new PhysicsSpec.Impulse(i,new Vec3(.01f*i,1,0),Vec3.ZERO,i%2==0));if(i>0)joints.add(joint(i-1,i));}
      long calls=batch.statistics().nativeCalls();var ids=batch.addStructure(bodies,joints);
      assertEquals(calls+1,batch.statistics().nativeCalls());assertEquals(count,ids.bodies().size());assertEquals(count-1,ids.joints().size());
      for(var b:bodies)scalar.addBody(b);for(var j:joints)scalar.addJoint(j);
      calls=batch.statistics().nativeCalls();batch.applyImpulses(impulses);assertEquals(calls+1,batch.statistics().nativeCalls());scalar.applyImpulses(impulses);
      var output=buffer(count*13+8);var snap=batch.snapshot();
      for(int frame=0;frame<30;frame++) {
        batch.step();scalar.step();output.clear().position(4).limit(count*13+4);
        calls=batch.statistics().nativeCalls();assertEquals(count,batch.readBodyStates(output));assertEquals(calls+1,batch.statistics().nativeCalls());assertEquals(count*13+4,output.position());
        var expected=scalar.bodyStates();for(int i=0;i<count;i++) {assertEquals(expected.get(i).pose().position().x(),output.get(4+i*13),1e-6);assertEquals(expected.get(i).pose().position().y(),output.get(5+i*13),1e-6);}
      }
      var after=batch.bodyStates();batch.restore(snap);for(int i=0;i<30;i++)batch.step();assertEquals(after,batch.bodyStates());
    }
  }

  @Test void invalidStructureAndImpulseBatchDoNotMutateTheWorld() {
    try(var w=NativePhysicsConformanceTest.nativeProvider().createWorld(PhysicsSpec.World.defaults())) {
      w.addBody(body(0));var original=w.bodyStates();long bytes=w.statistics().replayBytes();
      assertThrows(IllegalArgumentException.class,()->w.addStructure(List.of(body(1)),List.of(joint(0,999))));
      assertEquals(original,w.bodyStates());assertEquals(bytes,w.statistics().replayBytes());
      assertThrows(IllegalArgumentException.class,()->w.applyImpulses(List.of(new PhysicsSpec.Impulse(0,new Vec3(1,0,0),Vec3.ZERO,false),new PhysicsSpec.Impulse(999,Vec3.ZERO,Vec3.ZERO,false))));
      assertEquals(original,w.bodyStates());assertEquals(bytes,w.statistics().replayBytes());
      assertEquals(1,w.addStructure(List.of(body(1)),List.of(joint(0,1))).bodies().get(0));
    }
  }

  @Test void readbackRejectsBadBuffersWithoutPublishingPartialOutput() {
    try(var w=NativePhysicsConformanceTest.nativeProvider().createWorld(PhysicsSpec.World.defaults().withoutReplay())) {
      w.addBody(body(0));var shortBuffer=buffer(12);for(int i=0;i<12;i++)shortBuffer.put(i,123);
      assertThrows(IllegalArgumentException.class,()->w.readBodyStates(shortBuffer));assertEquals(0,shortBuffer.position());for(int i=0;i<12;i++)assertEquals(123,shortBuffer.get(i));
      assertThrows(IllegalArgumentException.class,()->w.readBodyStates(FloatBuffer.allocate(13)));
      assertThrows(IllegalArgumentException.class,()->w.readBodyStates(buffer(13).asReadOnlyBuffer()));
    }
  }

  @Test void independentWorldAnchorsAreDeterministicUnderParallelModels() throws Exception {
    var p=NativePhysicsConformanceTest.nativeProvider();var executor=Executors.newFixedThreadPool(4);
    try {
      Callable<List<PhysicsSpec.BodyState>> task=()->{try(var w=p.createWorld(PhysicsSpec.World.defaults().withoutReplay())){w.addStructure(List.of(body(0)),List.of(joint(-1,0)));for(int i=0;i<180;i++)w.step();return w.bodyStates();}};
      var results=executor.invokeAll(Collections.nCopies(4,task));var expected=results.get(0).get();for(var result:results)assertEquals(expected,result.get());
    } finally {executor.shutdownNow();}
  }

  @Test void packedPoseViewsAndSoftReadbackRespectOffsetsAndRejectAtomically() {
    try(var w=NativePhysicsConformanceTest.nativeProvider().createWorld(PhysicsSpec.World.defaults().withoutReplay())) {
      w.addStructure(List.of(body(0),body(1)),List.of());
      var ids=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asIntBuffer();ids.put(1,0).put(2,1).position(1).limit(3);
      var poses=buffer(18);poses.put(2,2).put(3,3).put(4,4).put(8,1).put(9,5).put(10,6).put(11,7).put(15,1).position(2).limit(16);
      long calls=w.statistics().nativeCalls();w.setBodyPoses(ids,poses);assertEquals(calls+1,w.statistics().nativeCalls());assertEquals(1,ids.position());assertEquals(2,poses.position());
      assertEquals(new Vec3(2,3,4),w.bodyStates().get(0).pose().position());assertEquals(new Vec3(5,6,7),w.bodyStates().get(1).pose().position());
      var before=w.bodyStates();ids.put(2,999);poses.put(2,99);assertThrows(IllegalArgumentException.class,()->w.setBodyPoses(ids,poses));assertEquals(before,w.bodyStates());
      int soft=w.addSoftBody(NativePhysicsConformanceTest.softSpec(NativePhysicsConformanceTest.SoftScenario.CLOTH));w.step();var output=buffer(16).position(2);
      calls=w.statistics().nativeCalls();assertEquals(4,w.readSoftBody(soft,output,false));assertEquals(calls+1,w.statistics().nativeCalls());assertEquals(14,output.position());
      var vertices=w.softBodyVertices(soft);for(int i=0;i<4;i++){assertEquals(vertices.get(i).x(),output.get(2+i*3));assertEquals(vertices.get(i).y(),output.get(3+i*3));}
    }
  }

  @Test void jniBoundaryRejectsNullAndMalformedArraysWithoutCrashing() throws Exception {
    NativePhysicsConformanceTest.nativeProvider();
    var body=NativePhysicsProvider.class.getDeclaredMethod("nBody",long.class,float[].class);body.setAccessible(true);
    assertEquals(-1,body.invoke(null,0L,null));
    var soft=NativePhysicsProvider.class.getDeclaredMethod("nSoft",long.class,float[].class,int[].class,int[].class,boolean.class,float.class,float[].class);soft.setAccessible(true);
    assertEquals(-1,soft.invoke(null,0L,new float[7],new int[]{0,1},new int[0],true,1f,null));
    var read=NativePhysicsProvider.class.getDeclaredMethod("nRead",long.class,float[].class);read.setAccessible(true);assertEquals(-1,read.invoke(null,0L,null));
  }

  @Test void batchPinsMatchSinglePinsReplaceDuplicateTargetsAndReplay() {
    var provider=NativePhysicsConformanceTest.nativeProvider();
    for(boolean replay:List.of(false,true))try(var batch=provider.createWorld(new PhysicsSpec.World(new Vec3(0,-9.8f,0),1f/60,10,replay));var scalar=provider.createWorld(PhysicsSpec.World.defaults())) {
      var spec=NativePhysicsConformanceTest.softSpec(NativePhysicsConformanceTest.SoftScenario.CLOTH);batch.addSoftBody(spec);scalar.addSoftBody(spec);
      var ids=ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asIntBuffer();ids.put(0,0).put(1,0);
      var positions=buffer(6);var original=spec.vertices().get(0);var checkpoint=replay?batch.snapshot():null;
      for(int step=0;step<30;step++) {
        positions.put(0,original.x()).put(1,original.y()).put(2,original.z()).put(3,original.x()+step*.01f).put(4,original.y()).put(5,original.z());
        long calls=batch.statistics().nativeCalls();var before=batch.softBodyVertices(0);batch.setSoftBodyPins(0,ids,positions);assertEquals(calls+2,batch.statistics().nativeCalls());assertEquals(before,batch.softBodyVertices(0));
        scalar.setSoftBodyPin(0,0,new Vec3(positions.get(3),positions.get(4),positions.get(5)));batch.step();scalar.step();assertEquals(scalar.softBodyVertices(0),batch.softBodyVertices(0));
      }
      var after=batch.softBodyVertices(0);
      ids.put(1,2);assertThrows(IllegalArgumentException.class,()->batch.setSoftBodyPins(0,ids,positions));assertEquals(after,batch.softBodyVertices(0));
      if(replay){batch.restore(checkpoint);ids.put(1,0);for(int step=0;step<30;step++){positions.put(3,original.x()+step*.01f);batch.setSoftBodyPins(0,ids,positions);batch.step();}assertEquals(after,batch.softBodyVertices(0));}
    }
  }
}
