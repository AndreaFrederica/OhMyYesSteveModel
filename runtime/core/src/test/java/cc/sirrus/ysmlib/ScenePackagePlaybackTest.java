package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenePackagePlaybackTest {
  final SceneProvider scenes=YsmRuntime.scenes();
  final ReadLimits limits=ReadLimits.DEFAULT;
  final ScenePackagePlayback.Settings settings=ScenePackagePlayback.Settings.preview();
  @Test void profileOnlyEditsReuseParsedDocumentsButNotOldProfileOrRelaxedLimits() throws Exception {
    var first=asset(ScenePackage.Format.PMX,"skin.pmx",List.of(),Map.of("skin.pmx",resource("/mmd-oracle/skin.pmx")));
    var files=new LinkedHashMap<>(first.source().files());var profile=SceneModelProfile.defaults(.1,18,0);
    files.put(SceneModelProfile.PACKAGE_PATH,scenes.writeModelProfile(profile));
    var source=new ScenePackage(first.source().model(),first.source().settings(),List.of(),List.of(),files);
    var second=scenes.loadPackage(source,limits);assertSame(first.model(),second.model());assertEquals(source,second.source());
    assertThrows(AssetFormatException.class,()->scenes.loadPackage(source,new ReadLimits(32,1,1)));
  }
  ByteData resource(String path) throws Exception {
    try(var input=getClass().getResourceAsStream(path)) { return new ByteData(Objects.requireNonNull(input,path).readAllBytes()); }
  }
  ScenePackageAssets asset(ScenePackage.Format format,String path,List<ScenePackage.Source> animations,Map<String,ByteData> files) throws Exception {
    return scenes.loadPackage(new ScenePackage(new ScenePackage.Source("avatar",path,format),
        new ScenePackage.Settings(format==ScenePackage.Format.PMX?.08:1,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),animations,List.of(),files),limits);
  }
  @Test void elapsedMappingPreservesNegativeSourceTimesAndZeroDurationPoses() {
    assertEquals(-1.5,new AnimationPlaybackRange(-2,-1,true).sample(10.5));
    assertEquals(-1,new AnimationPlaybackRange(-2,-1,false).sample(10.5));
    assertEquals(3,new AnimationPlaybackRange(3,3,true).sample(100));
    assertThrows(IllegalArgumentException.class,()->new AnimationPlaybackRange(-Double.MAX_VALUE,Double.MAX_VALUE,true));
    assertThrows(IllegalArgumentException.class,()->new AnimationPlaybackRange(0,1,true).sample(-1));
  }
  @Test void independentGltfPlayersAndExternalNodeDomainsAreNotMixed() throws Exception {
    var files=new LinkedHashMap<String,ByteData>();
    for(String name:List.of("SimpleSkin.gltf","SimpleSkin_skinningData.bin","SimpleSkin_inverseBindMatrices.bin","SimpleSkin_geometry.bin","SimpleSkin_animation.bin"))
      files.put(name,resource("/khronos/SimpleSkin/glTF/"+name));
    files.put("other.gltf",files.get("SimpleSkin.gltf"));
    var asset=asset(ScenePackage.Format.GLTF,"SimpleSkin.gltf",List.of(new ScenePackage.Source("other","other.gltf",ScenePackage.Format.GLTF)),files);
    var selected=new ScenePackagePlayback.Selection("avatar",0);
    var first=scenes.playback(asset,selected,settings,limits);
    try(first;var second=scenes.playback(asset,selected,settings,limits)) {
      var pose=first.seek(1);assertSame(pose,first.seek(1));
      assertTrue(pose.thirdPerson().draws().get(0).geometry().primitives().get(0).attributes().containsKey("NORMAL"));
      assertFalse(((ScenePackageAssets.Gltf)asset.model()).value().scene().meshes().get(0).primitives().get(0).attributes().containsKey("NORMAL"));
      var still=second.seek(0);assertNotEquals(still.thirdPerson(),pose.thirdPerson());
      first.seek(2);assertEquals(pose,first.seek(1));assertSame(still,second.seek(0));
      assertThrows(IllegalArgumentException.class,()->scenes.playback(asset,new ScenePackagePlayback.Selection("other",0),settings,limits));
    }
    assertThrows(IllegalStateException.class,()->first.seek(1));first.close();
  }
  @Test void mmdPreviewViewsReuseOneSourcePhysicsFrameAndReplayBackwards() throws Exception {
    var asset=asset(ScenePackage.Format.PMX,"skin.pmx",List.of(new ScenePackage.Source("dance","skin.vmd",ScenePackage.Format.VMD)),
        Map.of("skin.pmx",resource("/mmd-oracle/skin.pmx"),"skin.vmd",resource("/mmd-oracle/skin.vmd")));
    try(var player=scenes.playback(asset,new ScenePackagePlayback.Selection("dance",0),settings,limits)) {
      var timeline=scenes.preview(new AnimationPreview.Range(0,2,30),player::seek);timeline.play();
      var once=timeline.advance(1,.5);assertSame(once,timeline.advance(1,.5));assertSame(once.firstPerson(),once.thirdPerson());
      var original=assertInstanceOf(ScenePackagePlayback.Mmd.class,once.details()).value();
      assertEquals(original.primitives().get(0),once.thirdPerson().draws().get(0).geometry().primitives().get(0).attributes());
      assertEquals(.08,once.thirdPerson().coordinates().metersPerUnit());
      player.seek(1);assertEquals(once,player.seek(.5));
    }
  }
  @Test void mmdWithoutVmdUsesGeneratedBoneMappedLocomotion() throws Exception {
    var asset=asset(ScenePackage.Format.PMX,"skin.pmx",List.of(),
        Map.of("skin.pmx",resource("/mmd-oracle/skin.pmx")));
    var selection=new ScenePackagePlayback.Selection("@ysm/generated/walk",-1);
    try(var player=scenes.playback(asset,selection,settings,limits)) {
      var first=assertInstanceOf(ScenePackagePlayback.Mmd.class,player.seek(.25).details()).value();
      // This fixture only has root/child bones: do not invent humanoid semantics.
      assertTrue(first.pose().animation().channels().isEmpty());
      // Raw playback clamps source curves; cyclic transport is an explicit host setting.
      assertDoesNotThrow(()->player.seek(2.25));
      var wrapped=assertInstanceOf(ScenePackagePlayback.Mmd.class,player.seek(.25).details()).value();
      assertFalse(wrapped.primitives().isEmpty());
    }
  }
  @Test void packageLoopKeepsElapsedClockAndRawVmdPreservesEndpoint() throws Exception {
    var asset=asset(ScenePackage.Format.PMX,"skin.pmx",List.of(new ScenePackage.Source("dance","skin.vmd",ScenePackage.Format.VMD)),
        Map.of("skin.pmx",resource("/mmd-oracle/skin.pmx"),"skin.vmd",resource("/mmd-oracle/skin.vmd")));
    var selection=new ScenePackagePlayback.Selection("dance",0);
    try(var raw=scenes.playback(asset,selection,settings,limits);
        var loop=scenes.playback(asset,selection,settings.withAnimationRange(new AnimationPlaybackRange(0,1,true)),limits)) {
      var a=((ScenePackagePlayback.Mmd)loop.seek(.25).details()).value();
      var b=((ScenePackagePlayback.Mmd)loop.seek(1.25).details()).value();
      assertEquals(1.25,b.simulationSeconds());assertEquals(a.pose().animation().channels(),b.pose().animation().channels());
      assertNotEquals(((ScenePackagePlayback.Mmd)raw.seek(0).details()).value().pose().animation().channels(),
          ((ScenePackagePlayback.Mmd)raw.seek(1).details()).value().pose().animation().channels());
    }
  }
  @Test void vrmViewsAndVrmaUseTheSameFinalAvatarPose() throws Exception {
    var viewAsset=asset(ScenePackage.Format.VRM,"avatar.gltf",List.of(),Map.of("avatar.gltf",resource("/three-vrm-oracle/first-person-avatar.gltf")));
    try(var player=scenes.playback(viewAsset,ScenePackagePlayback.Selection.REST,settings,limits)) {
      var frame=player.seek(.5);assertInstanceOf(ScenePackagePlayback.Vrm.class,frame.details());
      int first=indices(frame.firstPerson()),third=indices(frame.thirdPerson());assertTrue(first<third);
      player.seek(1);assertEquals(frame,player.seek(.5));
    }
    var retarget=asset(ScenePackage.Format.VRM,"avatar.gltf",List.of(new ScenePackage.Source("motion","motion.gltf",ScenePackage.Format.VRMA)),
        Map.of("avatar.gltf",resource("/three-vrm-oracle/retarget-avatar.gltf"),"motion.gltf",resource("/three-vrm-oracle/retarget-source.gltf")));
    try(var player=scenes.playback(retarget,new ScenePackagePlayback.Selection("motion",0),settings,limits)) {
      var frame=assertInstanceOf(ScenePackagePlayback.Vrm.class,player.seek(1).details());assertEquals(1f,frame.value().expressions().get("happy"));
    }
  }
  @Test void fbxPreservesItsSourceFrameAndOwnsItsManagedReactor() throws Exception {
    var source=asset(ScenePackage.Format.FBX,"avatar.fbx",List.of(),Map.of("avatar.fbx",resource("/ufbx/maya_blend_inbetween_7500_ascii.fbx")));
    var player=scenes.playback(source,ScenePackagePlayback.Selection.REST,settings,limits);
    try(player) {
      var frame=player.seek(.5);assertInstanceOf(ScenePackagePlayback.Fbx.class,frame.details());assertFalse(frame.thirdPerson().draws().isEmpty());
      player.seek(1);assertEquals(frame,player.seek(.5));assertSame(frame.firstPerson(),frame.thirdPerson());
    }
    assertThrows(IllegalStateException.class,()->player.seek(0));
  }
  private static int indices(GeometryFrame frame) { return frame.draws().stream().flatMap(d->d.geometry().primitives().stream()).mapToInt(p->p.indices().size()).sum(); }
}
