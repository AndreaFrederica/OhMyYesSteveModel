package cc.sirrus.ysmlib.scene.physics.wasm;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.physics.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HostPhysicsTest {
  private final WasmPhysicsProvider provider=new WasmPhysicsProvider();
  private ScenePhysicsInput input(double t,double x,Matrix4 matrix,Vec3 gravity,List<ScenePhysicsInput.Box> solids,List<ScenePhysicsInput.Fluid> fluids){
    return new ScenePhysicsInput(t,x,30_000_000,30_000_000,matrix,gravity,solids,fluids,ScenePhysicsInput.Settings.defaults());
  }
  private int sphere(PhysicsWorld world,float mass,int mask){return world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,
      new Vec3(.1f,0,0),new Pose(new Vec3(0,2,0),Rotation.IDENTITY),PhysicsSpec.Motion.DYNAMIC,mass,0,0,0,.5f,.001f,1,mask));}
  @Test void largeWorldCoordinatesConstantVelocityAccelerationTurnAndTeleport(){
    var motion=new ScenePhysicsMotion();var identity=Matrix4.IDENTITY;
    assertTrue(motion.update(input(0,30_000_000,identity,Vec3.ZERO,List.of(),List.of())).reset());
    motion.update(input(.05,30_000_000.05,identity,Vec3.ZERO,List.of(),List.of()));
    var steady=motion.update(input(.1,30_000_000.1,identity,Vec3.ZERO,List.of(),List.of()));
    assertEquals(1,steady.velocity().x(),1e-6);assertEquals(0,steady.acceleration().x(),1e-5);
    var faster=motion.update(input(.15,30_000_000.2,identity,Vec3.ZERO,List.of(),List.of()));
    assertEquals(20,faster.acceleration().x(),1e-4);
    assertEquals(faster.acceleration(),motion.update(input(.15,30_000_000.2,identity,Vec3.ZERO,List.of(),List.of())).acceleration());
    var turned=new Transform(Vec3.ZERO,Rotation.axisAngle(new Vec3(0,1,0),.1),Vec3.ONE).matrix();
    var turn=motion.update(input(.2,30_000_000.3,turned,Vec3.ZERO,List.of(),List.of()));
    assertEquals(2,turn.angularVelocity().y(),1e-5);
    var teleport=motion.update(input(.25,30_000_010,turned,Vec3.ZERO,List.of(),List.of()));
    assertTrue(teleport.reset());assertEquals(Vec3.ZERO,teleport.acceleration());
    assertTrue(motion.update(input(1,30_000_010,turned,Vec3.ZERO,List.of(),List.of())).reset());
    var halfTurn=new Transform(Vec3.ZERO,Rotation.axisAngle(new Vec3(0,1,0),Math.PI+.1),Vec3.ONE).matrix();
    assertTrue(motion.update(input(1.05,30_000_010,halfTurn,Vec3.ZERO,List.of(),List.of())).reset());
  }
  @Test void fixedStepsApplyMassIndependentInertiaAndReflectedScaledGravity(){
    var matrix=new Transform(Vec3.ZERO,Rotation.axisAngle(new Vec3(0,0,1),Math.PI/2),new Vec3(.1f,.1f,-.1f)).matrix();
    var in=input(0,0,matrix,new Vec3(0,-9.8f,0),List.of(),List.of());
    try(var world=provider.createWorld(new PhysicsSpec.World(Vec3.ZERO,1f/60,10,false))){
      sphere(world,1,0);sphere(world,3,0);
      var e=new PhysicsEnvironment(in,Vec3.ZERO,new Vec3(0,6,0),Vec3.ZERO,Vec3.ZERO,false);world.environment(e);
      for(int i=0;i<60;i++)world.step();
      for(var body:world.bodyStates()){assertEquals(-158,body.linearVelocity().x(),.002);assertEquals(0,body.linearVelocity().y(),.002);}
      assertEquals(60,world.stepIndex());world.bodyStates();assertEquals(60,world.stepIndex());
    }
  }
  @Test void realTerrainContactsIgnoreSourceSelfMaskAndReplacementDoesNotGrowBodyIds(){
    var floor=new ScenePhysicsInput.Box(new Vec3(-2,-1,-2),new Vec3(2,0,2));
    try(var world=provider.createWorld(new PhysicsSpec.World(Vec3.ZERO,1f/60,10,true,true))){
      sphere(world,1,0);var env=new PhysicsEnvironment(input(0,0,Matrix4.IDENTITY,new Vec3(0,-9.8f,0),List.of(floor),List.of()),Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,false);
      world.environment(env);for(int i=0;i<120;i++){world.environment(env);world.step();}
      assertEquals(1,world.bodyStates().size());assertEquals(.1,world.bodyStates().get(0).pose().position().y(),.02);
      var saved=world.snapshot();world.environment(null);for(int i=0;i<60;i++)world.step();
      assertTrue(world.bodyStates().get(0).pose().position().y()<-4);
      world.restore(saved);assertEquals(.1,world.bodyStates().get(0).pose().position().y(),.02);
      world.environment(null);world.setGravity(Vec3.ZERO);world.setVelocity(0,Vec3.ZERO,Vec3.ZERO);world.step();
      assertEquals(1,world.bodyStates().size());
    }
  }
  @Test void flowingWaterMovesRigidAndSoftBodiesAndDragIsBounded(){
    var volume=new ScenePhysicsInput.Box(new Vec3(-100,-100,-100),new Vec3(100,100,100));
    var fluid=new ScenePhysicsInput.Fluid(volume,new Vec3(2,0,0),1);
    try(var world=provider.createWorld(new PhysicsSpec.World(Vec3.ZERO,1f/60,10,false))){
      sphere(world,1,0);
      int cloth=world.addSoftBody(new PhysicsSpec.SoftBody(List.of(new Vec3(0,2,3),new Vec3(1,2,3),new Vec3(0,2,4)),List.of(0,1,2),false,List.of(),1,.001f,1,0,PhysicsSpec.SoftConfig.defaults()));
      world.environment(new PhysicsEnvironment(input(0,0,Matrix4.IDENTITY,Vec3.ZERO,List.of(),List.of(fluid)),Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,false));
      for(int i=0;i<60;i++)world.step();
      assertTrue(world.bodyStates().get(0).linearVelocity().x()>1.8);assertTrue(world.bodyStates().get(0).linearVelocity().x()<2);
      assertTrue(world.softBodyVertices(cloth).get(0).x()>.5);
    }
  }
  @Test void rotatingFrameProducesAngularResponseInLeftHandedSource(){
    var matrix=new Transform(Vec3.ZERO,Rotation.IDENTITY,new Vec3(.1f,.1f,-.1f)).matrix();
    try(var world=provider.createWorld(new PhysicsSpec.World(Vec3.ZERO,1f/60,10,false))){
      sphere(world,1,0);world.environment(new PhysicsEnvironment(input(0,0,matrix,Vec3.ZERO,List.of(),List.of()),Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,new Vec3(0,2,0),false));
      world.step();assertEquals(2f/60,world.bodyStates().get(0).angularVelocity().y(),1e-5);
    }
  }
}
