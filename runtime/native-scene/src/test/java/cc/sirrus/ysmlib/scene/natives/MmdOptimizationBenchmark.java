package cc.sirrus.ysmlib.scene.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.JavaDeformationProvider;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.physics.*;
import cc.sirrus.ysmlib.scene.physics.natives.NativePhysicsProvider;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Real source-space complete logical frames. No GL, file decoding or oracle comparisons in timed loops. */
public final class MmdOptimizationBenchmark {
  private static final com.sun.management.ThreadMXBean MEMORY=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
  private static volatile Object sink;
  private record Mode(String name,PhysicsProvider physics,DeformationProvider deformation,boolean packed) {}
  private static MmdPlayer player(PmxDocument model,AnimationClip clip,Mode mode,boolean physics) {
    var settings=MmdPlayback.Settings.preview().withPhysics(physics);
    return mode.packed()?MmdPlayer.packed(model,clip,mode.physics(),settings,Map.of(),mode.deformation())
        :new MmdPlayer(model,clip,mode.physics(),settings,Map.of());
  }
  private static long allocated(){return MEMORY.getThreadAllocatedBytes(Thread.currentThread().getId());}
  private static double percentile(long[] times,double q){return times[Math.min(times.length-1,(int)Math.ceil(times.length*q)-1)]/1e6;}
  private static float error(MmdPlayback.Frame expected,MmdPlayback.Frame actual) {
    float worst=0;for(int p=0;p<expected.primitives().size();p++)for(var name:List.of("POSITION","NORMAL")){
      var a=expected.primitives().get(p).get(name).values();var b=actual.primitives().get(p).get(name).values();
      for(int i=0;i<a.size();i++)worst=Math.max(worst,Math.abs(a.get(i)-b.get(i)));}return worst;
  }
  public static void main(String[] args)throws Exception {
    int samples=Integer.getInteger("ysm.mmd.benchmark.samples",90),warmup=30;
    if(samples<10||samples>2000)throw new IllegalArgumentException("Benchmark sample count must be 10..2000");
    Path root=Path.of(System.getProperty("ysm.mmd.benchmark.corpus","D:/Projects/ysm/vrc-mmd"));
    List<Path> models,motions;try(var paths=Files.walk(root)){var files=paths.filter(Files::isRegularFile).sorted().toList();models=files.stream().filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".pmx")).toList();motions=files.stream().filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".vmd")).toList();}
    String extra=System.getProperty("ysm.mmd.benchmark.extraCorpus","");
    if(!extra.isBlank())try(var paths=Files.walk(Path.of(extra))){var combined=new ArrayList<>(models);combined.addAll(paths.filter(Files::isRegularFile).filter(p->FileSystems.getDefault().getPathMatcher("glob:"+System.getProperty("ysm.mmd.benchmark.extraModel","*")).matches(p.getFileName())).filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".pmx")).sorted().toList());models=List.copyOf(combined);}
    if(models.isEmpty()||motions.isEmpty())throw new IllegalArgumentException("Corpus needs PMX and character VMD");
    var nativePhysics=new NativePhysicsProvider(Path.of(Objects.requireNonNull(System.getProperty("ysm.native.physics.library"))));
    Path nativeSkinning=Path.of(Objects.requireNonNull(System.getProperty("ysm.native.skinning.library")));
    var java=new JavaDeformationProvider();var wasm=new WasmPhysicsProvider();
    var modes=List.of(new Mode("wasm-java-reference",wasm,java,false),new Mode("native-java-packed",nativePhysics,java,true),
        new Mode("native-skinning-scalar",nativePhysics,new NativeDeformationProvider(nativeSkinning,false),true),
        new Mode("native-skinning-parallel",nativePhysics,new NativeDeformationProvider(nativeSkinning,true),true));
    AnimationClip clip=null;Path selectedMotion=null;
    for(var path:motions){var vmd=new VmdReader().read(new ByteData(Files.readAllBytes(path)),ReadLimits.DEFAULT);if(!vmd.bones().isEmpty()){clip=new VmdAnimation().compile(vmd).clip();selectedMotion=path;break;}}
    if(clip==null)throw new IllegalArgumentException("No character motion in corpus");
    var rows=new ArrayList<String>();rows.add("model,modelSha256,motionSha256,mode,physics,instances,vertices,bones,rigids,joints,samples,warmup,constructionMs,p50Ms,p95Ms,p99Ms,javaBytesPerFrame,maxDeformationError");
    for(var path:models){byte[] bytes=Files.readAllBytes(path);var model=new PmxReader().read(new ByteData(bytes),ReadLimits.DEFAULT);var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      var motionHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(selectedMotion)));
      for(boolean physics:new boolean[]{false,true})for(var mode:modes) {
        float worst=0;
        // Isolate skinning error with identical physics; the existing physics corpus covers WASM/native drift.
        var oracleMode=new Mode("oracle",mode.physics(),java,mode.packed());
        try(var actual=player(model,clip,mode,physics);var oracle=player(model,clip,oracleMode,physics)){
          for(double time:new double[]{0,.1,.25,.503,1,.25,.508})worst=Math.max(worst,error(oracle.seek(time),actual.seek(time)));
        }
        if(!Float.isFinite(worst)||worst>1e-4)throw new AssertionError("Skinning corpus error="+worst+" mode="+mode.name()+" model="+path);
        if(Boolean.getBoolean("ysm.mmd.benchmark.verifyOnly")){System.out.println("Corpus verification passed: "+path.getFileName()+" mode="+mode.name()+" physics="+physics+" error="+worst);continue;}
        for(int count:new int[]{1,4}){
          var players=new ArrayList<MmdPlayer>();long construction=System.nanoTime();
          try {
            for(int i=0;i<count;i++)players.add(player(model,clip,mode,physics));construction=System.nanoTime()-construction;
            for(int i=1;i<=warmup;i++)for(var p:players)sink=p.seek(i/60d);
            var times=new long[samples];long memory=allocated();
            for(int i=0;i<samples;i++){double time=(warmup+i+1)/60d;long start=System.nanoTime();for(var p:players)sink=p.seek(time);times[i]=System.nanoTime()-start;}
            long allocated=allocated()-memory;Arrays.sort(times);
            String row=String.format(Locale.ROOT,"\"%s\",%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.1f,%.8f",(path.startsWith(root)?root.relativize(path):path.getFileName()).toString().replace("\"","\"\""),hash,motionHash,mode.name(),physics,count,model.vertices().size(),model.bones().size(),model.rigidBodies().size(),model.joints().size(),samples,warmup,construction/1e6,percentile(times,.5),percentile(times,.95),percentile(times,.99),allocated/(double)samples,worst);
            rows.add(row);System.out.println(row);
          }finally{for(var p:players)p.close();}
        }
      }
    }
    Path output=Path.of(args[0]);Files.createDirectories(output.toAbsolutePath().getParent());Files.write(output,rows);
    System.out.println("Complete MMD logical frame benchmark saved: "+output);
  }
}
