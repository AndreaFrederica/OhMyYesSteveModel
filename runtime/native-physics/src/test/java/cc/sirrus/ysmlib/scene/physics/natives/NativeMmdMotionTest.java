package cc.sirrus.ysmlib.scene.physics.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.mmd.MmdPhysicsMotion;
import cc.sirrus.ysmlib.scene.physics.*;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeMmdMotionTest {
  @Test void placementScaleRotationGravityInertiaAndResetMatchAnalyticalMotionAndWasm() {
    var settings=new PhysicsSpec.World(Vec3.ZERO,1f/60,10,false);
    try(var n=NativePhysicsConformanceTest.nativeProvider().createWorld(settings);var w=new WasmPhysicsProvider().createWorld(settings)) {
      for(var world:List.of(n,w))for(int i=0;i<2;i++)world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(.5f,0,0),new Pose(new Vec3(i*3,0,0),Rotation.IDENTITY),PhysicsSpec.Motion.DYNAMIC,i+1,0,0,0,0,.04f,1,0));
      var policy=new MmdPhysicsMotion.Settings(1f/60,1,20,30,10);
      var bodies=List.of(new MmdPhysicsMotion.Body(0,1),new MmdPhysicsMotion.Body(1,2));
      var nm=new MmdPhysicsMotion(n,policy,bodies);var wm=new MmdPhysicsMotion(w,policy,bodies);
      var rotation=Rotation.axisAngle(new Vec3(0,1,0),Math.PI/2);
      for(int frame=0;frame<3;frame++) {
        var input=new MmdPhysicsMotion.Input(new Vec3(frame*.1f,0,0),rotation,10,.1f,new Vec3(0,-9.8f,0));
        nm.beforeStep(input);wm.beforeStep(input);n.step();w.step();nm.afterStep();wm.afterStep();NativePhysicsConformanceTest.states(w,n,frame);
      }
      for(var state:n.bodyStates()){assertEquals(-98f*3/60,state.linearVelocity().y(),1e-4);assertEquals(10f/.1f*2/60,state.linearVelocity().z(),1e-4);assertEquals(0,state.linearVelocity().x(),1e-4);}
      nm.resetMotion();wm.resetMotion();
      var reset=new MmdPhysicsMotion.Input(new Vec3(1000,0,0),rotation,10,.1f,Vec3.ZERO);
      nm.beforeStep(reset);wm.beforeStep(reset);var before=n.bodyStates();n.step();w.step();nm.afterStep();wm.afterStep();NativePhysicsConformanceTest.states(w,n,4);
      for(int i=0;i<2;i++)assertEquals(before.get(i).linearVelocity(),n.bodyStates().get(i).linearVelocity());
      nm.resetMotion();wm.resetMotion();for(var world:List.of(n,w))for(int i=0;i<2;i++)world.resetBodyForces(i);
      var tilted=new MmdPhysicsMotion.Input(Vec3.ZERO,Rotation.axisAngle(new Vec3(0,0,1),Math.PI/2),10,.1f,new Vec3(0,-9.8f,2));
      nm.beforeStep(tilted);wm.beforeStep(tilted);n.step();w.step();nm.afterStep();wm.afterStep();NativePhysicsConformanceTest.states(w,n,5);
      for(var state:n.bodyStates()){assertEquals(-98f/60,state.linearVelocity().x(),1e-4);assertEquals(-20f/60,state.linearVelocity().z(),1e-4);assertEquals(0,state.linearVelocity().y(),1e-4);}
    }
  }
  @Test void teleportsAccelerationBudgetVelocityGuardAndKinematicExclusionAreBounded() {
    try(var world=NativePhysicsConformanceTest.nativeProvider().createWorld(new PhysicsSpec.World(Vec3.ZERO,1f/60,10,false))) {
      world.addBody(NativePhysicsBatchTest.body(0));world.addBody(NativePhysicsBatchTest.body(1));world.setKinematic(1,true);
      var motion=new MmdPhysicsMotion(world,new MmdPhysicsMotion.Settings(1f/60,1,20,5,2),List.of(new MmdPhysicsMotion.Body(0,1),new MmdPhysicsMotion.Body(1,1)));
      motion.beforeStep(new MmdPhysicsMotion.Input(Vec3.ZERO,Rotation.IDENTITY,10,.1f,Vec3.ZERO));
      motion.beforeStep(new MmdPhysicsMotion.Input(new Vec3(Float.MAX_VALUE,0,0),Rotation.IDENTITY,10,.1f,Vec3.ZERO));world.step();motion.afterStep();
      assertEquals(-5,world.bodyStates().get(0).linearVelocity().x(),1e-5);assertEquals(Vec3.ZERO,world.bodyStates().get(1).linearVelocity());
      world.setVelocity(0,new Vec3(0,100,0),new Vec3(0,0,100));motion.afterStep();
      assertEquals(new Vec3(0,5,0),world.bodyStates().get(0).linearVelocity());assertEquals(new Vec3(0,0,2),world.bodyStates().get(0).angularVelocity());
    }
  }
}
