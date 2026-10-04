package cc.sirrus.ysmlib.scene.physics.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.physics.*;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import java.lang.management.ManagementFactory;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** Explicit headless benchmark; timing is diagnostic evidence, never a unit-test assertion. */
public final class NativePhysicsBenchmark {
  static final int WARMUP=300,SAMPLES=600;
  static final List<String> rows=new ArrayList<>();
  static final com.sun.management.ThreadMXBean memory=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
  static FloatBuffer floats(int n){return ByteBuffer.allocateDirect(n*4).order(ByteOrder.nativeOrder()).asFloatBuffer();}
  static long allocated(){return memory.getThreadAllocatedBytes(Thread.currentThread().getId());}
  static void report(String mode,String workload,int bodies,int joints,int models,int soft,long build,long[] ns,long allocation,long calls) {
    Arrays.sort(ns);String row=String.format(Locale.ROOT,"%s,%s,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.1f,%.1f",mode,workload,bodies,joints,models,soft,build/1e6,ns[ns.length/2]/1e6,ns[(int)(ns.length*.95)]/1e6,ns[(int)(ns.length*.99)]/1e6,allocation/(double)ns.length,calls<0?-1:calls/(double)ns.length);
    rows.add(row);System.out.println(row);
  }
  static final class Model implements AutoCloseable {
    final PhysicsWorld world;final int bodies,soft;final boolean packed;
    final IntBuffer roots;final FloatBuffer poses,states,positions,normals;
    Model(PhysicsProvider p,int count,boolean packed,boolean cloth) {
      this.packed=packed;world=p.createWorld(PhysicsSpec.World.defaults().withoutReplay());bodies=count;
      var specs=new ArrayList<PhysicsSpec.Body>();var joints=new ArrayList<PhysicsSpec.Joint>();
      for(int i=0;i<count;i++) {
        boolean root=i%16==0;
        specs.add(new PhysicsSpec.Body(PhysicsSpec.Shape.CAPSULE_Y,new Vec3(.04f,.1f,0),new Pose(new Vec3(i/16,4-i%16*.2f,0),Rotation.IDENTITY),root?PhysicsSpec.Motion.KINEMATIC:PhysicsSpec.Motion.DYNAMIC,root?0:.1f,.05f,.05f,0,.5f,.02f,2,1));
        if(!root)joints.add(new PhysicsSpec.Joint(PhysicsSpec.JointType.SPRING_6DOF,i-1,i,new Pose(new Vec3(0,-.1f,0),Rotation.IDENTITY),new Pose(new Vec3(0,.1f,0),Rotation.IDENTITY),Vec3.ZERO,Vec3.ZERO,new Vec3(-.5f,-.5f,-.5f),new Vec3(.5f,.5f,.5f),Vec3.ZERO,new Vec3(.2f,.2f,.2f),.5f,true));
      }
      if(packed)world.addStructure(specs,joints);else{for(var b:specs)world.addBody(b);for(var j:joints)world.addJoint(j);}
      roots=ByteBuffer.allocateDirect(count/16*4).order(ByteOrder.nativeOrder()).asIntBuffer();poses=floats(count/16*7);states=floats(count*13);
      for(int i=0;i<count/16;i++){roots.put(i,i*16);poses.put(i*7+6,1);}
      if(cloth) {
        var vertices=new ArrayList<Vec3>();var indices=new ArrayList<Integer>();var pins=new ArrayList<Integer>();
        for(int z=0;z<8;z++)for(int x=0;x<8;x++){vertices.add(new Vec3(x*.1f,4-z*.1f,0));if(z==0)pins.add(x);}
        for(int z=0;z<7;z++)for(int x=0;x<7;x++){int a=z*8+x;indices.addAll(List.of(a,a+8,a+1,a+1,a+8,a+9));}
        soft=world.addSoftBody(new PhysicsSpec.SoftBody(vertices,indices,false,pins,1,.02f,1,0,PhysicsSpec.SoftConfig.defaults()));
      }else soft=-1;
      positions=floats(cloth?192:0);normals=floats(cloth?192:0);
    }
    void frame(int frame) {
      float y=4+(float)Math.sin(frame/30d)*.3f,z=(float)Math.cos(frame/20d)*.2f;
      if(packed){for(int i=0;i<roots.capacity();i++){poses.put(i*7,i).put(i*7+1,y).put(i*7+2,z);}world.setBodyPoses(roots,poses);}
      else for(int i=0;i<bodies;i+=16)world.setBodyPose(i,new Pose(new Vec3(i/16,y,z),Rotation.IDENTITY));
      world.step();
      if(packed){states.clear();world.readBodyStates(states);}else world.bodyStates();
      if(soft>=0) {
        if(packed){positions.clear();normals.clear();world.readSoftBody(soft,positions,false);world.readSoftBody(soft,normals,true);}
        else{world.softBodyVertices(soft);world.softBodyNormals(soft);}
      }
    }
    public void close(){world.close();}
  }
  static void run(String mode,PhysicsProvider provider,int count,int models,boolean packed,boolean cloth) {
    var worlds=new ArrayList<Model>();long start=System.nanoTime();
    try {
      for(int i=0;i<models;i++)worlds.add(new Model(provider,count,packed,cloth));long build=System.nanoTime()-start;
      for(int i=0;i<WARMUP;i++)for(var model:worlds)model.frame(i);
      long before=allocated(),calls=worlds.stream().mapToLong(m->m.world.statistics().nativeCalls()).sum();long[] times=new long[SAMPLES];
      for(int i=0;i<SAMPLES;i++){start=System.nanoTime();for(var model:worlds)model.frame(WARMUP+i);times[i]=System.nanoTime()-start;}
      long allocation=allocated()-before,after=worlds.stream().mapToLong(m->m.world.statistics().nativeCalls()).sum();
      report(mode,cloth?"cloth-and-chains":"moving-chains",count,count-count/16,models,cloth?64:0,build,times,allocation,calls<0?-1:after-calls);
    } finally {for(var model:worlds)model.close();}
  }
  public static void main(String[] args) throws Exception {
    String path=Objects.requireNonNull(System.getProperty("ysm.native.physics.library"),"Explicit native library required");
    memory.setThreadAllocatedMemoryEnabled(true);
    long start=System.nanoTime();var nativeProvider=new NativePhysicsProvider(Path.of(path));System.out.printf(Locale.ROOT,"nativeColdInitMs=%.3f%n",(System.nanoTime()-start)/1e6);
    start=System.nanoTime();var wasm=new WasmPhysicsProvider();try(var w=wasm.createWorld(PhysicsSpec.World.defaults().withoutReplay())){w.step();}System.out.printf(Locale.ROOT,"wasmColdInitMs=%.3f%n",(System.nanoTime()-start)/1e6);
    System.out.printf("java=%s os=%s arch=%s warmup=%d samples=%d%n",System.getProperty("java.version"),System.getProperty("os.name"),System.getProperty("os.arch"),WARMUP,SAMPLES);
    rows.add("mode,workload,bodiesPerModel,jointsPerModel,models,softVerticesPerModel,constructionMs,p50Ms,p95Ms,p99Ms,javaBytesPerFrame,jniCallsPerFrame");System.out.println(rows.get(0));
    for(int count:new int[]{32,128,256}){run("native-scalar",nativeProvider,count,1,false,false);run("native-packed",nativeProvider,count,1,true,false);run("wasm",wasm,count,1,true,false);}
    run("native-packed",nativeProvider,32,4,true,false);run("wasm",wasm,32,4,true,false);
    run("native-packed",nativeProvider,32,1,true,true);run("wasm",wasm,32,1,true,true);
    Path output=Path.of(args[0]);Files.createDirectories(output.toAbsolutePath().getParent());Files.write(output,rows);
  }
}
