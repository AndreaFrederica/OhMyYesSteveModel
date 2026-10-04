package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SceneDiskCacheTest {
  final ReadLimits limits=ReadLimits.DEFAULT;
  final SceneProvider scenes=YsmRuntime.scenes();
  ByteData resource(String name)throws Exception {try(var in=getClass().getResourceAsStream(name)){return new ByteData(Objects.requireNonNull(in,name).readAllBytes());}}
  ScenePackage pack(ScenePackage.Format format,String path,Map<String,ByteData> files,List<ScenePackage.Source> animations) {
    return new ScenePackage(new ScenePackage.Source("avatar",path,format),new ScenePackage.Settings(.08,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),animations,List.of(),files);
  }
  SceneDiskCache cache(Path root){return new SceneDiskCache(root,"tests",512L*1024*1024,256L*1024*1024);}
  SceneProvider forbidExpensiveWork() {
    return (SceneProvider)java.lang.reflect.Proxy.newProxyInstance(SceneProvider.class.getClassLoader(),new Class<?>[]{SceneProvider.class},(proxy,method,args)->{
      if(Set.of("loadPackage","readImage","texturePixels","playback").contains(method.getName()))throw new AssertionError("Warm cache reran "+method.getName());
      try{return method.invoke(scenes,args);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
    });
  }
  @Test void allHostFormatsRestoreDocumentsRestGeometryAndCurvesWithoutRebuilding(@TempDir Path root)throws Exception {
    var corpus=new ArrayList<ScenePackage>();
    for(String name:List.of("skin","morph"))corpus.add(pack(ScenePackage.Format.PMX,name+".pmx",Map.of(name+".pmx",resource("/mmd-oracle/"+name+".pmx"),"motion.vmd",resource("/mmd-oracle/"+name+".vmd")),List.of(new ScenePackage.Source("motion","motion.vmd",ScenePackage.Format.VMD))));
    corpus.add(pack(ScenePackage.Format.PMD,"pmd.pmd",Map.of("pmd.pmd",resource("/mmd-oracle/pmd.pmd"),"motion.vmd",resource("/mmd-oracle/pmd.vmd")),List.of(new ScenePackage.Source("motion","motion.vmd",ScenePackage.Format.VMD))));
    var gltf=new LinkedHashMap<String,ByteData>();for(String name:List.of("SimpleSkin.gltf","SimpleSkin_skinningData.bin","SimpleSkin_inverseBindMatrices.bin","SimpleSkin_geometry.bin","SimpleSkin_animation.bin"))gltf.put(name,resource("/khronos/SimpleSkin/glTF/"+name));
    corpus.add(pack(ScenePackage.Format.GLTF,"SimpleSkin.gltf",gltf,List.of()));
    corpus.add(pack(ScenePackage.Format.VRM,"avatar.gltf",Map.of("avatar.gltf",resource("/three-vrm-oracle/first-person-avatar.gltf")),List.of()));
    corpus.add(pack(ScenePackage.Format.FBX,"avatar.fbx",Map.of("avatar.fbx",resource("/ufbx/maya_blend_inbetween_7500_ascii.fbx")),List.of()));
    for(var source:corpus) {
      var events=new ArrayList<SceneDiskCache.Event>();var cold=cache(root).session(scenes,limits,events::add,()->false);
      var original=cold.loadPackage(source);var rest=cold.rest(original);
      assertTrue(events.stream().noneMatch(e->e.state().equals("uncached")),events.toString());
      var warm=cache(root).session(forbidExpensiveWork(),limits,events::add,()->false);
      var restored=warm.loadPackage(source);assertEquals(rest,warm.rest(restored),source.model().path());
      var baseline=scenes.loadPackage(source,limits);
      var selection=source.animations().isEmpty()?new ScenePackagePlayback.Selection("avatar",0):new ScenePackagePlayback.Selection("motion",0);
      if(source.model().format()==ScenePackage.Format.VRM)selection=ScenePackagePlayback.Selection.REST;
      try(var a=scenes.playback(baseline,selection,ScenePackagePlayback.Settings.preview().withPhysics(false),limits);
          var b=scenes.playback(restored,selection,ScenePackagePlayback.Settings.preview().withPhysics(false),limits)) {
        for(double time:new double[]{0,.25,1,.5})assertEquals(a.seek(time).thirdPerson(),b.seek(time).thirdPerson(),source.model().path()+" at "+time);
      }
      assertEquals(source,restored.source());
    }
  }
  @Test void imagesAndMaterialSamplesSurviveProfileOnlyChanges(@TempDir Path root)throws Exception {
    var source=new ScenePackageImagesTest().gltf(true);
    var cold=cache(root).session(scenes,limits,e->{},()->false);var assets=cold.loadPackage(source);var images=cold.images(assets);
    var usages=List.of(new SceneImageUsage(SceneImageUsage.Transfer.SRGB,SceneImageUsage.Alpha.STRAIGHT),new SceneImageUsage(SceneImageUsage.Transfer.SOURCE_NUMERIC,SceneImageUsage.Alpha.RAW));
    var expected=new ArrayList<FloatData>();for(var image:images.images().values())for(var usage:usages)expected.add(cold.texturePixels(image,usage,limits));
    var files=new LinkedHashMap<>(source.files());files.put(SceneModelProfile.PACKAGE_PATH,scenes.writeModelProfile(SceneModelProfile.defaults(.08,18,0)));
    var edited=new ScenePackage(source.model(),source.settings(),source.animations(),source.relocations(),files);
    var events=new ArrayList<SceneDiskCache.Event>();var warm=cache(root).session(forbidExpensiveWork(),limits,events::add,()->false);
    var restored=warm.loadPackage(edited);assertEquals(files.get(SceneModelProfile.PACKAGE_PATH),restored.source().files().get(SceneModelProfile.PACKAGE_PATH));
    var actual=warm.images(restored);int i=0;for(var image:actual.images().values())for(var usage:usages) {
      var samples=warm.texturePixels(image,usage,limits);assertEquals(expected.get(i),samples);assertEquals(expected.get(i++).storageBytes(),samples.storageBytes());
    }
    assertTrue(events.stream().noneMatch(e->e.state().equals("building")),events.toString());
  }
  @Test void corruptEntriesAreRebuiltAndSourceVersionAndLimitsInvalidate(@TempDir Path root)throws Exception {
    var source=pack(ScenePackage.Format.PMX,"skin.pmx",Map.of("skin.pmx",resource("/mmd-oracle/skin.pmx")),List.of());
    var events=new ArrayList<SceneDiskCache.Event>();var disk=cache(root);disk.session(scenes,limits,events::add,()->false).loadPackage(source);
    Path entry;try(var paths=Files.list(root)){entry=paths.filter(p->p.getFileName().toString().startsWith("documents-")).findFirst().orElseThrow();}
    var bytes=Files.readAllBytes(entry);bytes[bytes.length-1]^=1;Files.write(entry,bytes);events.clear();
    disk.session(scenes,limits,events::add,()->false).loadPackage(source);
    assertTrue(events.stream().anyMatch(e->e.state().equals("building")&&e.reason().contains("integrity")),events.toString());
    events.clear();new SceneDiskCache(root,"new-profile",512L*1024*1024,256L*1024*1024).session(scenes,limits,events::add,()->false).loadPackage(source);
    assertTrue(events.stream().anyMatch(e->e.state().equals("stored")));
    assertThrows(AssetFormatException.class,()->disk.session(scenes,new ReadLimits(100,100,20),e->{},()->false).loadPackage(source));
    var changed=new LinkedHashMap<>(source.files());changed.put("extra.bmp",new ByteData(new byte[]{1}));events.clear();
    disk.session(scenes,limits,events::add,()->false).loadPackage(new ScenePackage(source.model(),source.settings(),source.animations(),source.relocations(),changed));
    assertTrue(events.stream().anyMatch(e->e.state().equals("stored")));
  }
  @Test void unavailableCacheDoesNotPreventLoadingAndCancellationDoesNotPublish(@TempDir Path root)throws Exception {
    var source=pack(ScenePackage.Format.PMX,"skin.pmx",Map.of("skin.pmx",resource("/mmd-oracle/skin.pmx")),List.of());
    var blocked=Files.writeString(root.resolve("file"),"not a directory");var events=new ArrayList<SceneDiskCache.Event>();
    assertNotNull(cache(blocked).session(scenes,limits,events::add,()->false).loadPackage(source));
    assertTrue(events.stream().anyMatch(e->e.state().equals("uncached")));
    assertThrows(java.util.concurrent.CancellationException.class,()->cache(root).session(scenes,limits,e->{},()->true).loadPackage(source));
  }
  @Test void boundedDiskStorageEvictsDerivedFilesWithoutTouchingSource(@TempDir Path root)throws Exception {
    var source=pack(ScenePackage.Format.PMX,"skin.pmx",Map.of("skin.pmx",resource("/mmd-oracle/skin.pmx")),List.of());
    var protectedFile=Files.writeString(root.resolve("keep.pmx"),"user file");
    var events=new ArrayList<SceneDiskCache.Event>();
    var tiny=new SceneDiskCache(root,"bounded-test",16000,16000);
    for(int i=0;i<4;i++) {
      var files=new HashMap<>(source.files());files.put("stamp.txt",new ByteData(new byte[]{(byte)i}));
      tiny.session(scenes,limits,events::add,()->false).loadPackage(new ScenePackage(source.model(),source.settings(),source.animations(),source.relocations(),files));
    }
    try(var paths=Files.list(root)) {
      long total=paths.filter(p->p.toString().endsWith(".ysc")).mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new RuntimeException(e);}}).sum();
      assertTrue(total<=16000);assertTrue(total>0,events.toString());
    }
    assertEquals("user file",Files.readString(protectedFile));
  }
  @Test void wireRejectsUnknownTypesOversizedAllocationsAndForwardReferences()throws Exception {
    for(byte[] payload:List.of(new byte[]{127},new byte[]{1,0,0,0,0},new byte[]{13,127,-1,-1,-1,0},new byte[]{10,127,-1}))
      assertThrows(java.io.IOException.class,()->SceneCacheCodec.read(new java.io.ByteArrayInputStream(payload),Map.of(),4096,100,100));
    assertThrows(java.io.IOException.class,()->SceneCacheCodec.write(new java.io.ByteArrayOutputStream(),new java.io.File("inert"),Map.of()));
  }
}
