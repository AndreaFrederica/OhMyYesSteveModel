package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.nio.file.*;
import java.util.*;
import java.security.DigestOutputStream;
import java.io.OutputStream;

/** Fresh-JVM verification using the same cache API as the game. Does not modify source assets. */
public final class SceneCacheVerification {
  public static void main(String[] args)throws Exception {
    var limits=new ReadLimits(1024*1024*1024,100_000_000,4*1024*1024);
    if(args[0].equals("capture")) {
      String sourcePath=args[1].startsWith("@")?Files.readString(Path.of(args[1].substring(1))).trim():args[1];
      var source=SceneAuthoring.capture(Path.of(sourcePath),limits,System.out::println);
      Files.write(Path.of(args[2]),YsmRuntime.scenes().writePackage(source,limits).copy());
      Files.writeString(Path.of(args[2]+".classpath"),System.getProperty("java.class.path"));return;
    }
    var scenes=YsmRuntime.scenes();
    var source=scenes.readPackage(new ByteData(Files.readAllBytes(Path.of(args[1]))),limits);
    boolean hot=args[0].equals("hot");
    SceneProvider provider=(SceneProvider)java.lang.reflect.Proxy.newProxyInstance(SceneProvider.class.getClassLoader(),new Class<?>[]{SceneProvider.class},(proxy,method,values)->{
      if(hot && Set.of("loadPackage","readImage","texturePixels","playback").contains(method.getName()))throw new AssertionError("Warm process rebuilt "+method.getName());
      try{return method.invoke(scenes,values);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
    });
    var events=new ArrayList<SceneDiskCache.Event>();
    var session=new SceneDiskCache(Path.of(args[2]),"verification1",4L*1024*1024*1024,1024L*1024*1024)
        .session(provider,limits,event->{events.add(event);System.out.println(event);},()->false);
    boolean baseline=args[0].equals("uncached");
    var memory=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
    long before=memory.getThreadAllocatedBytes(Thread.currentThread().getId());
    long start=System.nanoTime();var assets=baseline?scenes.loadPackage(source,limits):session.loadPackage(source);
    SceneDiskCache.Rest rest;
    if(baseline)try(var player=scenes.playback(assets,ScenePackagePlayback.Selection.REST,ScenePackagePlayback.Settings.preview().withPhysics(false),limits)) {
      var frame=player.seek(0);rest=new SceneDiskCache.Rest(frame.thirdPerson(),frame.firstPerson(),null,List.of(),scenes.animations(assets,30));
    } else rest=session.rest(assets);
    var images=baseline?scenes.images(assets,limits):session.images(assets);
    var pixels=new ArrayList<FloatData>();
    for(var image:images.images().values()) {
      var usage=new SceneImageUsage(SceneImageUsage.Transfer.SOURCE_NUMERIC,SceneImageUsage.Alpha.STRAIGHT);
      pixels.add(baseline?scenes.texturePixels(image,usage,limits):session.texturePixels(image,usage,limits));
    }
    long elapsed=(System.nanoTime()-start)/1_000_000;
    long allocated=memory.getThreadAllocatedBytes(Thread.currentThread().getId())-before;
    var digest=SceneCacheCodec.digest();var out=new DigestOutputStream(OutputStream.nullOutputStream(),digest);
    // Fixed semantic order independent of Map iteration order across processes.
    for(var draw:rest.thirdPerson().draws())for(var primitive:draw.geometry().primitives()) {
      SceneCacheCodec.write(out,primitive.indices(),Map.of());
      for(var attribute:new TreeMap<>(primitive.attributes()).values())SceneCacheCodec.write(out,attribute.values(),Map.of());
    }
    var ordered=new ArrayList<>(images.images().entrySet());ordered.sort(Comparator.comparing(e->e.getKey().toString()));
    for(var entry:ordered)SceneCacheCodec.write(out,entry.getValue().rgba(),Map.of());
    String hash=HexFormat.of().formatHex(digest.digest());
    if(hot) {
      String cold=Files.readString(Path.of(args[3]));if(!cold.contains("sha256="+hash))throw new AssertionError("Cold/hot geometry or pixels differ");
      if(events.stream().anyMatch(e->Set.of("building","uncached").contains(e.state())))throw new AssertionError("Hot process missed cache");
    }
    String result="mode="+args[0]+"\nmodel="+source.model().path()+"\nelapsedMs="+elapsed+"\nsha256="+hash+"\nimages="+images.images().size()+"\nthreadAllocatedBytes="+allocated+"\nevents="+events+"\n";
    Files.writeString(Path.of(args[3]+(hot?".hot":baseline?".baseline":"")),result);System.out.println(result);
  }
}
