package cc.sirrus.ysmlib.scene.physics.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.mmd.PmxReader;
import cc.sirrus.ysmlib.scene.physics.*;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import java.nio.file.*;
import java.util.*;

/** Isolates damping/libm from contacts and constraints using local corpus coefficients. */
public final class NativePhysicsNumerics {
  public static void main(String[] args) throws Exception {
    var damping=new TreeSet<Float>();damping.addAll(List.of(0f,.04f,.06f,.1f,.5f,1f));
    try(var paths=Files.walk(Path.of(args[0]))) {
      for(var path:paths.filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".pmx")).toList()) {
        var model=new PmxReader().read(new ByteData(Files.readAllBytes(path)),ReadLimits.DEFAULT);
        for(var body:model.rigidBodies()){damping.add(body.linearDamping());damping.add(body.angularDamping());}
      }
    }
    var provider=new NativePhysicsProvider(Path.of(System.getProperty("ysm.native.physics.library")));
    try(var n=provider.createWorld(new PhysicsSpec.World(Vec3.ZERO,1f/60,10,false));
        var w=new WasmPhysicsProvider().createWorld(new PhysicsSpec.World(Vec3.ZERO,1f/60,10,false))) {
      int id=0;
      for(float value:damping) {
        var body=new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(1,1,1),new Pose(new Vec3(id*5,0,0),Rotation.IDENTITY),PhysicsSpec.Motion.DYNAMIC,1,value,value,0,0,.02f,1,0);
        n.addBody(body);w.addBody(body);
        n.applyImpulse(id,new Vec3(1,0,0),new Vec3(.4f,0,0),false);w.applyImpulse(id,new Vec3(1,0,0),new Vec3(.4f,0,0),false);id++;
      }
      for(int step=1;step<=3;step++) {
        n.step();w.step();var ns=n.bodyStates();var ws=w.bodyStates();id=0;
        for(float value:damping) {
          var a=ns.get(id);var b=ws.get(id++);
          System.out.printf(Locale.ROOT,"step=%d damping=%s nativeLinear=%a wasmLinear=%a nativeAngular=%a wasmAngular=%a positionError=%s%n",step,value,a.linearVelocity().x(),b.linearVelocity().x(),a.angularVelocity().x(),b.angularVelocity().x(),a.pose().position().subtract(b.pose().position()));
        }
      }
    }
  }
}
