package cc.sirrus.ysmlib.scene.physics.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.physics.*;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import java.nio.file.*;
import java.util.*;

/** Measures complete publish/step/readback with host contacts and fluid input, never asserts timing. */
public final class NativeHostPhysicsBenchmark {
  static double run(String name,PhysicsProvider provider,int count,boolean moving) {
    var solids=new ArrayList<ScenePhysicsInput.Box>();var fluids=new ArrayList<ScenePhysicsInput.Fluid>();
    for(int x=-2;x<3;x++)for(int z=-2;z<3;z++){
      solids.add(new ScenePhysicsInput.Box(new Vec3(x,-1,z),new Vec3(x+1,0,z+1)));
      fluids.add(new ScenePhysicsInput.Fluid(new ScenePhysicsInput.Box(new Vec3(x,0,z),new Vec3(x+1,1,z+1)),new Vec3(.2f,0,0),1));
    }
    var samples=new long[180];
    try(var model=new NativePhysicsBenchmark.Model(provider,count,true,true)){
      for(int frame=0;frame<300;frame++){
        double yaw=moving?Math.sin(frame/50d)*.3:0;
        var matrix=new Transform(Vec3.ZERO,Rotation.axisAngle(new Vec3(0,1,0),yaw),new Vec3(.1f,.1f,-.1f)).matrix();
        var input=new ScenePhysicsInput(frame/60d,30_000_000,0,0,matrix,new Vec3(0,-9.8f,0),solids,fluids,ScenePhysicsInput.Settings.defaults());
        var env=new PhysicsEnvironment(input,new Vec3(.2f,0,0),new Vec3(.3f,0,0),new Vec3(0,.1f,0),new Vec3(0,.2f,0),false);
        long start=System.nanoTime();model.world.environment(env);model.frame(frame);
        if(frame>=120)samples[frame-120]=System.nanoTime()-start;
      }
    }
    Arrays.sort(samples);double median=samples[samples.length/2]/1e6;
    System.out.printf(Locale.ROOT,"%s,bodies=%d,moving=%s,p50Ms=%.4f,p95Ms=%.4f%n",name,count,moving,median,samples[(int)(samples.length*.95)]/1e6);return median;
  }
  public static void main(String[] args){
    var nativeProvider=new NativePhysicsProvider(Path.of(Objects.requireNonNull(System.getProperty("ysm.native.physics.library"))));
    var wasm=new WasmPhysicsProvider();
    for(boolean moving:List.of(false,true)){
      double n=run("native",nativeProvider,128,moving),w=run("wasm",wasm,128,moving);
      System.out.printf(Locale.ROOT,"wasm/native p50 ratio=%.2f%n",w/n);
    }
  }
}
