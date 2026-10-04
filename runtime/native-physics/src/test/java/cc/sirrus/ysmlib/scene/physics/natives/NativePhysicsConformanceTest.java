package cc.sirrus.ysmlib.scene.physics.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.physics.*;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class NativePhysicsConformanceTest {
  static NativePhysicsProvider nativeProvider() {
    String path=System.getProperty("ysm.native.physics.library");
    Assumptions.assumeTrue(path!=null,"Native test needs an explicit library path");
    return new NativePhysicsProvider(Path.of(path));
  }
  static Pose at(float x,float y,float z) { return new Pose(new Vec3(x,y,z),Rotation.IDENTITY); }
  static PhysicsSpec.Body body(PhysicsSpec.Shape shape,PhysicsSpec.Motion motion,Pose pose) {
    return new PhysicsSpec.Body(shape,new Vec3(.3f,.5f,.4f),pose,motion,motion==PhysicsSpec.Motion.DYNAMIC?2:0,.04f,.06f,.1f,.4f,.017f,1,0xffff);
  }
  static void vector(Vec3 expected,Vec3 actual,float tolerance,String label) {
    assertEquals(expected.x(),actual.x(),tolerance,label+" x");assertEquals(expected.y(),actual.y(),tolerance,label+" y");assertEquals(expected.z(),actual.z(),tolerance,label+" z");
  }
  static void states(PhysicsWorld expected,PhysicsWorld actual,int step) {
    var e=expected.bodyStates();var a=actual.bodyStates();assertEquals(e.size(),a.size());
    for(int i=0;i<e.size();i++) {
      String label="step="+step+" body="+i;
      vector(e.get(i).pose().position(),a.get(i).pose().position(),.002f,label);
      vector(e.get(i).linearVelocity(),a.get(i).linearVelocity(),.005f,label+" velocity");
      vector(e.get(i).angularVelocity(),a.get(i).angularVelocity(),.005f,label+" angular velocity");
      var q=e.get(i).pose().rotation();var r=a.get(i).pose().rotation();
      double dot=q.x()*r.x()+q.y()*r.y()+q.z()*r.z()+q.w()*r.w();assertEquals(1,Math.abs(dot),.002,label+" quaternion");
    }
  }
  static void soft(PhysicsWorld expected,int ei,PhysicsWorld actual,int ai,int step) {
    var e=expected.softBodyVertices(ei);var a=actual.softBodyVertices(ai);assertEquals(e.size(),a.size());
    var en=expected.softBodyNormals(ei);var an=actual.softBodyNormals(ai);
    for(int i=0;i<e.size();i++) { vector(e.get(i),a.get(i),.002f,"step="+step+" vertex="+i);vector(en.get(i),an.get(i),.005f,"step="+step+" normal="+i); }
  }
  @ParameterizedTest @EnumSource(PhysicsSpec.Shape.class)
  void collisionsAnimatedKinematicAndResumeMatchWasm(PhysicsSpec.Shape shape) {
    var settings=new PhysicsSpec.World(new Vec3(0,-9.8f,0),1f/60,4,false);
    try(var n=nativeProvider().createWorld(settings);var w=new WasmPhysicsProvider().createWorld(settings)) {
      for(var world:List.of(n,w)) {
        world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.BOX,new Vec3(10,.5f,10),at(0,-.5f,0),PhysicsSpec.Motion.STATIC,0,0,0,0,.5f,.017f,1,0xffff));
        world.addBody(body(shape,PhysicsSpec.Motion.DYNAMIC,at(0,3,0)));
      }
      for(int step=1;step<=150;step++) {
        for(var world:List.of(n,w)) {
          if(step==31){world.setKinematic(1,true);world.setBodyPose(1,at(1,4,0));}
          if(step==41){world.setKinematic(1,false);world.setGravity(new Vec3(0,-5,0));world.applyImpulse(1,new Vec3(1,0,0),new Vec3(0,.1f,0),true);}
          if(step==60)world.resetBodyForces(1);
          world.step();
        }
        states(w,n,step);
      }
    }
  }
  @ParameterizedTest @EnumSource(PhysicsSpec.JointType.class)
  void jointLimitsSpringsWorldAnchorAndMotorTrajectoryMatchWasm(PhysicsSpec.JointType type) {
    var settings=new PhysicsSpec.World(new Vec3(0,-9.8f,0),1f/60,7,false);
    try(var n=nativeProvider().createWorld(settings);var w=new WasmPhysicsProvider().createWorld(settings)) {
      for(var world:List.of(n,w)) {
        world.addBody(body(PhysicsSpec.Shape.BOX,PhysicsSpec.Motion.DYNAMIC,Pose.IDENTITY));
        int joint=world.addJoint(new PhysicsSpec.Joint(type,-1,0,Pose.IDENTITY,Pose.IDENTITY,new Vec3(-1,-2,-1),new Vec3(1,2,1),new Vec3(-.5f,-.4f,-.3f),new Vec3(.5f,.4f,.3f),new Vec3(20,40,10),new Vec3(2,3,4),.5f,true));
        if(type==PhysicsSpec.JointType.CONE_TWIST)world.configureJoint(joint,new PhysicsSpec.ConeTwistOptions(.5f,.4f,.3f,.8f,.3f,1,.1f,.05f,true,.5f,Rotation.axisAngle(new Vec3(0,1,0),.2f)));
        if(type==PhysicsSpec.JointType.SLIDER)world.configureJoint(joint,new PhysicsSpec.SliderOptions(true,1,4,true,.2f,1));
        if(type==PhysicsSpec.JointType.HINGE)world.configureJoint(joint,new PhysicsSpec.HingeOptions(.8f,.3f,1,true,.2f,.5f));
        world.applyImpulse(0,new Vec3(.3f,.2f,.1f),new Vec3(.1f,.2f,.3f),false);
      }
      for(int step=1;step<=90;step++){n.step();w.step();states(w,n,step);}
    }
  }
  enum SoftScenario { CLOTH, ROPE, DISCONNECTED, STIFFNESS_BENDING, CLUSTERS_ZERO, CLUSTERS_TWO, VOLUME_POSE }
  @ParameterizedTest @EnumSource(value=PhysicsSpec.JointType.class,names={"SPRING_6DOF","GENERIC_6DOF"})
  void perAxisErpCfmTrajectoryMatchesWasmAndReplays(PhysicsSpec.JointType type) {
    var settings=PhysicsSpec.World.defaults();
    try(var n=nativeProvider().createWorld(settings);var w=new WasmPhysicsProvider().createWorld(settings)) {
      for(var world:List.of(n,w)) {
        world.addBody(body(PhysicsSpec.Shape.BOX,PhysicsSpec.Motion.DYNAMIC,Pose.IDENTITY));
        int j=world.addJoint(new PhysicsSpec.Joint(type,-1,0,Pose.IDENTITY,Pose.IDENTITY,new Vec3(-.2f,-.2f,-.2f),new Vec3(.2f,.2f,.2f),new Vec3(-.2f,-.2f,-.2f),new Vec3(.2f,.2f,.2f),new Vec3(10,20,30),new Vec3(3,4,5),.5f,true));
        world.configureJoint(j,new PhysicsSpec.SixDofOptions(new Vec3(.1f,.2f,.3f),new Vec3(.4f,.5f,.6f),new Vec3(.001f,.002f,.003f),new Vec3(.004f,.005f,.006f)));
        world.applyImpulse(0,new Vec3(1,0,1),new Vec3(.1f,.2f,.3f),false);
      }
      var checkpoint=n.snapshot();for(int step=0;step<90;step++){n.step();w.step();states(w,n,step);}var after=n.bodyStates();n.restore(checkpoint);for(int step=0;step<90;step++)n.step();assertEquals(after,n.bodyStates());
    }
  }
  static PhysicsSpec.SoftBody softSpec(SoftScenario scenario) {
    var d=PhysicsSpec.SoftConfig.defaults();
    boolean clusters=scenario==SoftScenario.CLUSTERS_ZERO||scenario==SoftScenario.CLUSTERS_TWO;
    boolean stiffness=scenario==SoftScenario.STIFFNESS_BENDING;
    var cfg=new PhysicsSpec.SoftConfig(d.velocityCorrection(),.05f,.02f,.01f,0,scenario==SoftScenario.VOLUME_POSE?.1f:0,.3f,scenario==SoftScenario.VOLUME_POSE?.05f:0,
        d.rigidHardness(),d.kineticHardness(),d.softHardness(),d.anchorHardness(),d.softRigidHardness(),d.softKineticHardness(),d.softSoftHardness(),d.softRigidSplit(),d.softKineticSplit(),d.softSoftSplit(),
        0,6,0,4,stiffness?.25f:1,stiffness?.45f:1,stiffness?.6f:1,0,clusters?0x22:0x11,scenario==SoftScenario.CLUSTERS_TWO?2:0,stiffness?2:0,clusters,stiffness);
    if(scenario==SoftScenario.ROPE) return new PhysicsSpec.SoftBody(List.of(new Vec3(0,4,0),new Vec3(1,3,0),new Vec3(0,3,0),new Vec3(1,4,0)),List.of(0,2,2,1,1,3),true,List.of(0),2,.017f,1,0xffff,cfg);
    if(scenario==SoftScenario.DISCONNECTED) return new PhysicsSpec.SoftBody(List.of(new Vec3(-1,3,0),new Vec3(0,3,0),new Vec3(-1,3,1),new Vec3(1,3,0),new Vec3(2,3,0),new Vec3(1,3,1)),List.of(0,1,2,3,4,5),false,List.of(0),2,.017f,1,0xffff,cfg);
    return new PhysicsSpec.SoftBody(List.of(new Vec3(-1,3,0),new Vec3(1,3,0),new Vec3(-1,3,1),new Vec3(1,3,1)),List.of(0,2,1,1,2,3),false,List.of(0),2,.017f,1,0xffff,cfg);
  }
  @ParameterizedTest @EnumSource(SoftScenario.class)
  void fullSoftConfigTopologyAnchorAnimatedPinAndNormalsMatchWasm(SoftScenario scenario) {
    var settings=new PhysicsSpec.World(new Vec3(0,-9.8f,0),1f/60,10,false);
    try(var n=nativeProvider().createWorld(settings);var w=new WasmPhysicsProvider().createWorld(settings)) {
      var spec=softSpec(scenario);int ni,wi;
      for(var world:List.of(n,w))world.addBody(body(PhysicsSpec.Shape.BOX,PhysicsSpec.Motion.KINEMATIC,at(1,3,0)));
      ni=n.addSoftBody(spec);wi=w.addSoftBody(spec);
      n.anchorSoftBody(ni,1,0,true);w.anchorSoftBody(wi,1,0,true);
      for(int step=1;step<=60;step++) {
        var target=spec.vertices().get(0).add(new Vec3(step*.005f,0,0));
        var old=n.softBodyVertices(ni);n.setSoftBodyPin(ni,0,target);w.setSoftBodyPin(wi,0,target);assertEquals(old,n.softBodyVertices(ni),"pin target must not publish until step");
        n.setBodyPose(0,at(1+step*.003f,3,0));w.setBodyPose(0,at(1+step*.003f,3,0));n.step();w.step();soft(w,wi,n,ni,step);states(w,n,step);
      }
    }
  }
  @Test void nativeSnapshotsRestoreSolverHistoryAndRejectForeignOrLiveCheckpoints() {
    var p=nativeProvider();
    try(var world=p.createWorld(PhysicsSpec.World.defaults());var other=p.createWorld(PhysicsSpec.World.defaults());var live=p.createWorld(PhysicsSpec.World.defaults().withoutReplay())) {
      int rigid=world.addBody(body(PhysicsSpec.Shape.BOX,PhysicsSpec.Motion.DYNAMIC,at(0,2,0))),soft=world.addSoftBody(softSpec(SoftScenario.CLOTH));
      for(int i=0;i<30;i++)world.step();var snapshot=world.snapshot();var before=world.bodyStates();var beforeSoft=world.softBodyVertices(soft);
      world.applyImpulse(rigid,new Vec3(0,2,0),Vec3.ZERO,false);for(int i=0;i<20;i++)world.step();var after=world.bodyStates();
      world.restore(snapshot);assertEquals(30,world.stepIndex());assertEquals(before,world.bodyStates());assertEquals(beforeSoft,world.softBodyVertices(soft));
      world.applyImpulse(rigid,new Vec3(0,2,0),Vec3.ZERO,false);for(int i=0;i<20;i++)world.step();assertEquals(after,world.bodyStates());
      assertThrows(IllegalArgumentException.class,()->other.restore(snapshot));assertThrows(IllegalStateException.class,live::snapshot);
      assertThrows(IllegalArgumentException.class,()->world.softBodyVertices(-1));assertThrows(IllegalArgumentException.class,()->world.setSoftBodyPin(soft,2,Vec3.ZERO));
    }
  }
  @Test void localForcesVelocityGuardResetAndPendingForceReplayMatchWasm() {
    var settings=new PhysicsSpec.World(Vec3.ZERO,1f/60,10);
    try(var n=nativeProvider().createWorld(settings);var w=new WasmPhysicsProvider().createWorld(settings)) {
      var rotated=new Pose(Vec3.ZERO,Rotation.axisAngle(new Vec3(0,0,1),(float)Math.PI/2));
      for(var world:List.of(n,w))world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(.25f,0,0),rotated,PhysicsSpec.Motion.DYNAMIC,2,0,0,0,.5f,.04f,1,0));
      var force=new PhysicsSpec.Force(0,new Vec3(60,0,0),Vec3.ZERO,true);n.applyForces(List.of(force));w.applyForces(List.of(force));
      var pending=n.snapshot();n.step();w.step();states(w,n,1);assertEquals(.5f,n.bodyStates().get(0).linearVelocity().y(),1e-5);
      var once=n.bodyStates();n.restore(pending);n.step();assertEquals(once,n.bodyStates());n.step();w.step();states(w,n,2);assertEquals(.5f,n.bodyStates().get(0).linearVelocity().y(),1e-5,"force must clear after one step");
      assertThrows(IllegalArgumentException.class,()->n.applyForces(List.of(force,new PhysicsSpec.Force(999,Vec3.ZERO,Vec3.ZERO,false))));n.step();w.step();states(w,n,3);
      for(var world:List.of(n,w)) {
        world.setVelocity(0,new Vec3(30,40,0),new Vec3(0,0,20));world.clampVelocities(new int[]{0},3,4);
      }
      states(w,n,4);assertEquals(3,Math.sqrt(n.bodyStates().get(0).linearVelocity().dot(n.bodyStates().get(0).linearVelocity())),1e-5);assertEquals(4,n.bodyStates().get(0).angularVelocity().z(),1e-5);
      var guarded=n.bodyStates();assertThrows(IllegalArgumentException.class,()->n.clampVelocities(new int[]{0,999},1,1));assertEquals(guarded,n.bodyStates());
      for(var world:List.of(n,w)){world.applyForces(List.of(force));world.resetBodyForces(0);world.step();}states(w,n,5);assertEquals(Vec3.ZERO,n.bodyStates().get(0).linearVelocity());
    }
  }
  @Test void collisionMasksAndDisableLinkedCollisionMatchWasm() {
    for(int variant=0;variant<3;variant++) {
      var settings=new PhysicsSpec.World(Vec3.ZERO,1f/60,10,false);
      try(var n=nativeProvider().createWorld(settings);var w=new WasmPhysicsProvider().createWorld(settings)) {
        for(var world:List.of(n,w)) {
          world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(.5f,0,0),Pose.IDENTITY,PhysicsSpec.Motion.DYNAMIC,1,0,0,0,0,.04f,1,variant==2?0:0xffff));
          world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(.5f,0,0),at(.5f,0,0),PhysicsSpec.Motion.DYNAMIC,1,0,0,0,0,.04f,2,0xffff));
          world.addJoint(new PhysicsSpec.Joint(PhysicsSpec.JointType.GENERIC_6DOF,0,1,Pose.IDENTITY,Pose.IDENTITY,new Vec3(1,1,1),Vec3.ZERO,new Vec3(1,1,1),Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,.5f,variant==1));
        }
        for(int step=0;step<30;step++){n.step();w.step();states(w,n,step);}
        if(variant==0)assertTrue(n.bodyStates().get(1).pose().position().x()>.7f);
        else assertEquals(.5f,n.bodyStates().get(1).pose().position().x(),1e-5,"suppressed contacts must not separate overlapping bodies");
      }
    }
  }
}
