package cc.sirrus.ysmlib.scene.physics.wasm;

import cc.sirrus.ysmlib.scene.Pose;
import cc.sirrus.ysmlib.scene.Rotation;
import cc.sirrus.ysmlib.scene.Vec3;
import cc.sirrus.ysmlib.scene.physics.PhysicsSpec;
import cc.sirrus.ysmlib.scene.physics.PhysicsWorld;
import java.util.Arrays;

/** Explicit diagnostic workload, not a performance assertion in ordinary tests. */
public final class PhysicsBenchmark {
  public static void main(String[] args) {
    long startup=System.nanoTime();
    var provider=new WasmPhysicsProvider();
    try(var warm=provider.createWorld(PhysicsSpec.World.defaults().withoutReplay())) { warm.step(); }
    System.out.printf("provider=%s java=%s os=%s arch=%s coldInitMs=%.3f%n",provider.id(),System.getProperty("java.version"),
        System.getProperty("os.name"),System.getProperty("os.arch"),(System.nanoTime()-startup)/1e6);
    for(int bodies:new int[]{32,128}) {
      try(var world=provider.createWorld(PhysicsSpec.World.defaults().withoutReplay())) {
        int previous=-1;
        for(int i=0;i<bodies;i++) {
          boolean root=i%16==0;
          int body=world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.CAPSULE_Y,new Vec3(.04f,.1f,0),
              new Pose(new Vec3(i/16,4-(i%16)*.2f,0),Rotation.IDENTITY),root?PhysicsSpec.Motion.KINEMATIC:PhysicsSpec.Motion.DYNAMIC,
              root?0:.1f,.05f,.05f,0,.5f,.02f,2,1));
          if(!root) world.addJoint(new PhysicsSpec.Joint(PhysicsSpec.JointType.SPRING_6DOF,previous,body,
              new Pose(new Vec3(0,-.1f,0),Rotation.IDENTITY),new Pose(new Vec3(0,.1f,0),Rotation.IDENTITY),
              Vec3.ZERO,Vec3.ZERO,new Vec3(-.5f,-.5f,-.5f),new Vec3(.5f,.5f,.5f),Vec3.ZERO,new Vec3(.2f,.2f,.2f),.5f,true));
          previous=body;
        }
        for(int i=0;i<300;i++) { drive(world,bodies,i);world.step(); }
        long[] times=new long[1200];
        for(int i=0;i<times.length;i++) {
          long start=System.nanoTime();drive(world,bodies,i+300);world.step();world.bodyStates();times[i]=System.nanoTime()-start;
        }
        Arrays.sort(times);
        System.out.printf("workload=moving-anchored-chains bodies=%d joints=%d stepHz=60 iterations=10 samples=%d meanMs=%.4f p50Ms=%.4f p95Ms=%.4f p99Ms=%.4f%n",
            bodies,bodies-bodies/16,times.length,Arrays.stream(times).average().orElseThrow()/1e6,
            times[times.length/2]/1e6,times[(int)(times.length*.95)]/1e6,times[(int)(times.length*.99)]/1e6);
      }
    }
  }
  private static void drive(PhysicsWorld world,int bodies,int step) {
    double t=step/60.0;
    for(int root=0;root<bodies;root+=16) world.setBodyPose(root,
        new Pose(new Vec3(root/16,4+(float)Math.sin(t*2)*.3f,(float)Math.cos(t*3)*.2f),Rotation.IDENTITY));
  }
}
