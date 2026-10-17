package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Explicit benchmark, excluded from unit tests. Same changing palettes and morphs in both paths. */
public final class SceneDeformationBenchmark {
  private static volatile float sink;
  public static void main(String[] args){
    var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
    for(int vertices:new int[]{10000,50000})for(int influences:new int[]{4,8}){
      var mesh=CompiledSceneDeformerTest.mesh(vertices,influences);var fast=new CompiledSceneDeformer(mesh);var reference=new Deformer();
      var palettes=new ArrayList<List<Matrix4>>();for(int i=0;i<90;i++)palettes.add(CompiledSceneDeformerTest.palette(influences,i));var weights=new FloatData(.5f);
      for(int i=0;i<80;i++){consume(reference.deform(mesh,palettes.get(i%90),weights,Deformer.NormalMode.INVERSE_TRANSPOSE));consume(fast.deform(palettes.get(i%90),weights));}
      for(String mode:List.of("reference","compiled")){
        long beforeBytes=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();int frames=180;
        for(int i=0;i<frames;i++)consume(mode.equals("reference")?reference.deform(mesh,palettes.get(i%90),weights,Deformer.NormalMode.INVERSE_TRANSPOSE):fast.deform(palettes.get(i%90),weights));
        long elapsed=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-beforeBytes;
        System.out.printf(Locale.ROOT,"vertices=%d influences=%d mode=%s millisecondsPerFrame=%.4f allocatedBytesPerFrame=%d%n",vertices,influences,mode,elapsed/1e6/frames,bytes/frames);
      }
    }
  }
  private static void consume(Map<String,MeshAsset.Attribute> output){sink=output.get("POSITION").values().get(0);}
}
