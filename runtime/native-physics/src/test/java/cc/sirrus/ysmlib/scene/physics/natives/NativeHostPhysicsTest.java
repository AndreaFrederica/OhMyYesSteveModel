package cc.sirrus.ysmlib.scene.physics.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.physics.*;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeHostPhysicsTest {
  @Test void terrainSwimmingForcesAngularInertiaSoftBodiesAndReplayMatchWasm(){
    var provider=NativePhysicsConformanceTest.nativeProvider();
    for(boolean replay:List.of(false,true))try(var nativeWorld=provider.createWorld(new PhysicsSpec.World(Vec3.ZERO,1f/60,10,replay));
        var wasmWorld=new WasmPhysicsProvider().createWorld(new PhysicsSpec.World(Vec3.ZERO,1f/60,10,replay))) {
      for(var world:List.of(nativeWorld,wasmWorld)){
        world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(.1f,0,0),new Pose(new Vec3(0,2,0),Rotation.IDENTITY),PhysicsSpec.Motion.DYNAMIC,1,0,0,0,.5f,.001f,1,0));
        world.addSoftBody(new PhysicsSpec.SoftBody(List.of(new Vec3(0,2,3),new Vec3(1,2,3),new Vec3(0,2,4)),List.of(0,1,2),false,List.of(),1,.001f,1,0,PhysicsSpec.SoftConfig.defaults()));
      }
      PhysicsWorld.Snapshot saved=null;List<PhysicsSpec.BodyState> atSnapshot=null;
      for(int step=0;step<180;step++){
        double pitch=step<90?0:(step-90)*.01;
        var matrix=new Transform(Vec3.ZERO,Rotation.axisAngle(new Vec3(1,0,0),pitch),new Vec3(.1f,.1f,-.1f)).matrix();
        var floor=new ScenePhysicsInput.Box(new Vec3(-10,-.1f,-10),new Vec3(10,0,10));
        var fluids=step<45?List.<ScenePhysicsInput.Fluid>of():List.of(new ScenePhysicsInput.Fluid(new ScenePhysicsInput.Box(new Vec3(-2,-2,-2),new Vec3(2,2,2)),new Vec3(.2f,0,0),1));
        var input=new ScenePhysicsInput(step/60d,30_000_000,0,0,matrix,new Vec3(0,-9.8f,0),step<150?List.of(floor):List.of(),fluids,ScenePhysicsInput.Settings.defaults());
        var environment=new PhysicsEnvironment(input,new Vec3(.1f,0,0),new Vec3(step<30?.1f:0,0,0),new Vec3((float)(pitch==0?0:.6),0,0),new Vec3(step==90?.5f:0,0,0),step==120);
        for(var world:List.of(nativeWorld,wasmWorld)){world.environment(environment);world.step();}
        NativePhysicsConformanceTest.states(wasmWorld,nativeWorld,step);NativePhysicsConformanceTest.soft(wasmWorld,0,nativeWorld,0,step);
        assertEquals(1,nativeWorld.bodyStates().size());
        if(step==100&&replay){saved=nativeWorld.snapshot();atSnapshot=nativeWorld.bodyStates();}
      }
      if(replay){nativeWorld.restore(saved);assertEquals(atSnapshot,nativeWorld.bodyStates());}
      assertTrue(nativeWorld.statistics().nativeCalls()>0);
    }
  }
  @Test void oneLiveEnvironmentPublicationIsOneNativeCallAndNeverRetainsReplay(){
    var provider=NativePhysicsConformanceTest.nativeProvider();
    try(var world=provider.createWorld(PhysicsSpec.World.defaults().withoutReplay())){
      var input=new ScenePhysicsInput(0,0,0,0,Matrix4.IDENTITY,Vec3.ZERO,List.of(),List.of(),ScenePhysicsInput.Settings.defaults());
      var env=new PhysicsEnvironment(input,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,false);
      long calls=world.statistics().nativeCalls();for(int i=0;i<100;i++)world.environment(env);
      assertEquals(calls+100,world.statistics().nativeCalls());assertEquals(0,world.statistics().replayBytes());
      world.environment(null);assertEquals(calls+101,world.statistics().nativeCalls());
    }
  }
}
