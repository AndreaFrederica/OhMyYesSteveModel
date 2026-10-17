package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneModelFeaturesTest {
  final SceneProvider scenes=YsmRuntime.scenes();
  @Test void cameraOffsetsRoundTripAndOldMapsKeepZeroOffsets() {
    var p=SceneModelProfile.defaults(.08,18,0);var f=p.features();
    var fp=new SceneModelFeatures.FirstPerson(List.of(),"",0,0,0,List.of(),.5f,.02,.04,.25,.1,.2,.3);
    p=p.withFeatures(new SceneModelFeatures(f.expressions(),f.animations(),f.itemRules(),f.layers(),f.socketSpace(),f.procedural(),fp));
    var bytes=scenes.writeModelProfile(p);
    assertEquals(fp,scenes.readModelProfile(bytes).features().firstPerson());
    var old=new String(bytes.copy(),java.nio.charset.StandardCharsets.UTF_8)
        .replaceAll(",\\s*\"(?:camera|origin)[XYZ]\"\\s*:\\s*[-0-9.]+","");
    var restored=scenes.readModelProfile(new ByteData(old.getBytes(java.nio.charset.StandardCharsets.UTF_8))).features().firstPerson();
    assertEquals(0,restored.cameraX());assertEquals(0,restored.cameraY());assertEquals(0,restored.cameraZ());assertEquals(0,restored.originX());assertEquals(0,restored.originY());assertEquals(0,restored.originZ());
    assertThrows(IllegalArgumentException.class,()->new SceneModelFeatures.FirstPerson(List.of(),"",0,0,0,List.of(),.5f,0,0,Double.NaN));
  }
  @Test void vrmDirectMorphClipsAndManualInputsPreserveVersionExpressionRules() throws Exception {
    var positions=new MeshAsset.Attribute(3,new FloatData(0,0,0,1,0,0,0,1,0));
    var mesh=new MeshAsset("face",List.of(new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,Map.of("POSITION",positions),new IntData(0,1,2),-1,null,
        List.of(new MeshAsset.MorphTarget("smile",Map.of("POSITION",positions))))),new FloatData(0));
    var nodes=List.of(new SceneAsset.Node("head",new IntData(1),Transform.IDENTITY,null,-1,-1,-1,-1,FloatData.EMPTY,Map.of()),
        new SceneAsset.Node("face",IntData.EMPTY,Transform.IDENTITY,null,0,-1,-1,-1,FloatData.EMPTY,Map.of()));
    var coverage=new CompatibilityReport(List.of(),List.of());
    var scene=new SceneAsset("avatar",SceneAsset.Coordinates.GLTF,nodes,List.of(new SceneAsset.Scene("",new IntData(0),Map.of())),0,
        List.of(mesh),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),coverage);
    var binary=new cc.sirrus.ysmlib.scene.vrm.VrmDocument.Expression("happy","happy","happy",true,
        cc.sirrus.ysmlib.scene.vrm.VrmDocument.Override.NONE,cc.sirrus.ysmlib.scene.vrm.VrmDocument.Override.NONE,cc.sirrus.ysmlib.scene.vrm.VrmDocument.Override.NONE,
        List.of(new cc.sirrus.ysmlib.scene.vrm.VrmDocument.MorphBind(1,0,1)),List.of(),List.of());
    var avatar=new cc.sirrus.ysmlib.scene.vrm.VrmDocument(new GltfDocument(scene,new ByteData(new byte[0]),Map.of()),cc.sirrus.ysmlib.scene.vrm.VrmDocument.Version.VRM_1,
        Map.of("head",0),List.of(binary),List.of(),List.of(),List.of(),List.of(),List.of(),null,null,List.of(),coverage);
    var source=new ScenePackage(new ScenePackage.Source("model","avatar.vrm",ScenePackage.Format.VRM),new ScenePackage.Settings(1,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),List.of(),List.of(),Map.of("avatar.vrm",new ByteData(new byte[0])));
    var a=new ScenePackageAssets(source,Map.of("model",new ScenePackageAssets.Vrm(avatar)),Map.of());
    var raw=new SceneModelFeatures.Expression(List.of(new SceneModelFeatures.ExpressionTarget(SceneModelFeatures.Target.NODE_MORPH,1,0,"smile",1)),List.of(),0,0);
    var semantic=new SceneModelFeatures.Expression(List.of(new SceneModelFeatures.ExpressionTarget(SceneModelFeatures.Target.VRM_EXPRESSION,-1,-1,"happy",1)),List.of(),0,0);
    var track=new SceneModelFeatures.Track(SceneModelFeatures.Target.EXPRESSION,"raw",AnimationClip.Property.MORPH_WEIGHTS,SceneModelFeatures.Interpolation.LINEAR,List.of(new SceneModelFeatures.Key(0,List.of(0f)),new SceneModelFeatures.Key(1,List.of(.6f))));
    var f=new SceneModelFeatures(Map.of("raw",raw,"happy",semantic),Map.of("ramp",new SceneModelFeatures.Clip(1,30,List.of(track))),List.of(),List.of(),SceneModelFeatures.SocketSpace.BONE,SceneModelFeatures.Procedural.defaults(),SceneModelFeatures.FirstPerson.defaults());
    var settings=ScenePackagePlayback.Settings.preview().withPhysics(false).withModelProfile(SceneModelProfile.defaults(1,2,0).withFeatures(f));
    try(var p=scenes.playback(a,ScenePackagePlayback.Selection.REST,settings,ReadLimits.DEFAULT)){
      p.expressions(Map.of("happy",.3f));assertEquals(0,((ScenePackagePlayback.Vrm)p.seek(.5).details()).value().pose().morphWeights().get(1).get(0));
      p.expressions(Map.of("happy",.8f));assertEquals(1,((ScenePackagePlayback.Vrm)p.seek(.5).details()).value().pose().morphWeights().get(1).get(0));
      p.expressions(Map.of("happy",.8f,"raw",.4f));var frame=((ScenePackagePlayback.Vrm)p.seek(.5).details()).value();assertEquals(.4,frame.pose().morphWeights().get(1).get(0),1e-5);assertEquals(1,frame.expressions().get("happy"));
      p.expressions(Map.of("happy",.8f));assertEquals(1,((ScenePackagePlayback.Vrm)p.seek(.5).details()).value().pose().morphWeights().get(1).get(0));
    }
    try(var p=scenes.playback(a,new ScenePackagePlayback.Selection("@ysm/profile/ramp",-1),settings,ReadLimits.DEFAULT)){
      var frame=p.seek(.5);assertEquals(.3,((ScenePackagePlayback.Vrm)frame.details()).value().pose().morphWeights().get(1).get(0),1e-5);
      p.expressions(Map.of("raw",1f));assertEquals(1,((ScenePackagePlayback.Vrm)p.seek(.5).details()).value().pose().morphWeights().get(1).get(0));
      p.expressions(Map.of());assertEquals(frame,p.seek(.5));
    }
  }
  ScenePackageAssets asset() throws Exception {
    try(var in=getClass().getResourceAsStream("/mmd-oracle/morph.pmx")){
      var bytes=new ByteData(Objects.requireNonNull(in).readAllBytes());var pmx=scenes.readPmx(bytes,ReadLimits.DEFAULT);
      var source=new ScenePackage(new ScenePackage.Source("model","face.pmx",ScenePackage.Format.PMX),
          new ScenePackage.Settings(.08,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),List.of(),List.of(),Map.of("face.pmx",bytes));
      return new ScenePackageAssets(source,Map.of("model",new ScenePackageAssets.Pmx(pmx,scenes.mesh(pmx))),Map.of());
    }
  }
  SceneModelProfile profile(ScenePackageAssets a) {
    var pmx=((ScenePackageAssets.Pmx)a.model()).value();
    var target=new SceneModelFeatures.ExpressionTarget(SceneModelFeatures.Target.MMD_MORPH,-1,0,pmx.morphs().get(0).names().local(),1);
    var face=new SceneModelFeatures.Expression(List.of(target),List.of("blink"),0,0);
    var curve=new SceneModelFeatures.Track(SceneModelFeatures.Target.EXPRESSION,"happy",AnimationClip.Property.MORPH_WEIGHTS,SceneModelFeatures.Interpolation.LINEAR,
        List.of(new SceneModelFeatures.Key(0,List.of(0f)),new SceneModelFeatures.Key(1,List.of(.6f))));
    var f=new SceneModelFeatures(Map.of("happy",face,"blink",face),Map.of("smile",new SceneModelFeatures.Clip(1,30,List.of(curve))),List.of(),List.of(),SceneModelFeatures.SocketSpace.BONE,SceneModelFeatures.Procedural.defaults(),SceneModelFeatures.FirstPerson.defaults());
    return SceneModelProfile.defaults(.08,18,0).withFeatures(f);
  }
  @Test void authoredMorphRunsWithoutVmdAndManualReleaseRestoresAnimationAtSameTime() throws Exception {
    var a=asset();var profile=profile(a);var selected=new ScenePackagePlayback.Selection("@ysm/profile/smile",-1);
    try(var player=scenes.playback(a,selected,ScenePackagePlayback.Settings.preview().withPhysics(false).withModelProfile(profile),ReadLimits.DEFAULT)) {
      var source=player.seek(.5);float expected=((ScenePackagePlayback.Mmd)source.details()).value().pose().morphs().meshWeights().get(0);
      assertEquals(.3,expected,1e-5);
      player.expressions(Map.of("happy",1f));assertEquals(1,((ScenePackagePlayback.Mmd)player.seek(.5).details()).value().pose().morphs().meshWeights().get(0),1e-5);
      player.expressions(Map.of());assertEquals(source,player.seek(.5));
      assertTrue(player.select(ScenePackagePlayback.Selection.REST,null));assertEquals(0,((ScenePackagePlayback.Mmd)player.seek(.5).details()).value().pose().morphs().meshWeights().get(0),1e-5);
      assertThrows(IllegalArgumentException.class,()->player.expressions(Map.of("missing",1f)));
    }
  }
  @Test void armLayerCannotClaimFaceUnlessItsMorphMaskExplicitlyIncludesIt() throws Exception {
    var a=asset();var p=profile(a);try(var player=scenes.playback(a,ScenePackagePlayback.Selection.REST,ScenePackagePlayback.Settings.preview().withPhysics(false).withModelProfile(p),ReadLimits.DEFAULT)) {
      var selection=new ScenePackagePlayback.Selection("@ysm/profile/smile",-1);
      player.seek(.5);player.layers(List.of(new ScenePackagePlayback.Layer(selection,1,1,Set.of(),Set.of())));
      assertEquals(0,((ScenePackagePlayback.Mmd)player.seek(.5).details()).value().pose().morphs().meshWeights().get(0),1e-5);
      player.layers(List.of(new ScenePackagePlayback.Layer(selection,1,1,Set.of(),Set.of(0))));
      assertEquals(.6,((ScenePackagePlayback.Mmd)player.seek(.5).details()).value().pose().morphs().meshWeights().get(0),1e-5);
      player.layers(List.of());assertEquals(0,((ScenePackagePlayback.Mmd)player.seek(.5).details()).value().pose().morphs().meshWeights().get(0),1e-5);
    }
  }
  @Test void strictRoundTripAndOrdinaryAuthoringEditsPreserveFeatures() throws Exception {
    var p=profile(asset());assertEquals(p,scenes.readModelProfile(scenes.writeModelProfile(p)));
    var edited=p.withBones(p.bones()).withPlacement(p.placement()).withActions(p.actions()).withRetarget(p.retarget()).withHostPhysics(p.hostPhysics()).withHeldItems(p.heldItems()).withTransitions(p.transitions());assertEquals(p,edited);
    String json=new String(scenes.writeModelProfile(p).copy(),java.nio.charset.StandardCharsets.UTF_8);
    assertThrows(IllegalArgumentException.class,()->scenes.readModelProfile(new ByteData(json.replace("\"interval\"","\"intervalTypo\"").getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    var a=asset();var bytes=new LinkedHashMap<>(a.source().files());bytes.put(SceneModelProfile.PACKAGE_PATH,scenes.writeModelProfile(p));var s=new ScenePackage(a.source().model(),a.source().settings(),List.of(),List.of(),bytes);
    var embedded=new ScenePackageAssets(s,a.documents(),a.dependencies());assertTrue(scenes.animations(embedded,30).stream().anyMatch(c->c.selection().sourceId().equals("@ysm/profile/smile")));
  }
  @Test void categoryRulesCanBeOverriddenBySpecificItemHandAndState() {
    var p=SceneModelProfile.defaults(.08,18,0);var broad=new SceneModelFeatures.Match(List.of(),List.of(),List.of("block"),SceneModelFeatures.Hand.ANY,SceneModelFeatures.View.ANY,SceneModelFeatures.ItemState.ANY,"");
    var specific=new SceneModelFeatures.Match(List.of("minecraft:torch"),List.of(),List.of(),SceneModelFeatures.Hand.OFF,SceneModelFeatures.View.THIRD_PERSON,SceneModelFeatures.ItemState.IDLE,"");
    var rules=List.of(new SceneModelFeatures.ItemRule("block",10,broad,p.heldItems().right(),SceneModelFeatures.Display.FIXED,"vanilla"),new SceneModelFeatures.ItemRule("torch",20,specific,p.heldItems().left(),SceneModelFeatures.Display.THIRD_PERSON,"vanilla"));
    var f=new SceneModelFeatures(Map.of(),Map.of(),rules,List.of(),SceneModelFeatures.SocketSpace.BONE,SceneModelFeatures.Procedural.defaults(),SceneModelFeatures.FirstPerson.defaults());
    var ctx=new SceneModelFeatures.ItemContext("minecraft:torch",Set.of(),Set.of("block","torch"),true,false,false,SceneModelFeatures.ItemState.IDLE,"NONE");
    assertEquals("leftHand",f.attachment(p,ctx).socket().binding());
    assertEquals(SceneModelFeatures.Display.FIXED,f.attachment(p,new SceneModelFeatures.ItemContext("minecraft:stone",Set.of(),Set.of("block"),false,true,true,SceneModelFeatures.ItemState.IDLE,"NONE")).display());
  }
  @Test void staleMorphAndAuthoredBoneScaleAreRejected() throws Exception {
    var a=asset();var p=profile(a);var wrong=new SceneModelFeatures.ExpressionTarget(SceneModelFeatures.Target.MMD_MORPH,-1,0,"stale",1);
    var f=p.features();var changed=new SceneModelFeatures(Map.of("happy",new SceneModelFeatures.Expression(List.of(wrong),List.of(),0,0)),Map.of(),List.of(),List.of(),f.socketSpace(),f.procedural(),f.firstPerson());
    assertThrows(IllegalArgumentException.class,()->SceneFeatureBindings.validate(a,p.withFeatures(changed)));
  }
  @Test void itemAndBlockTagDomainsRemainSeparateAndSpecificBlocksCanOverrideCategories() {
    var itemTag=new SceneModelFeatures.Match(List.of(),List.of("example:wood"),List.of(),SceneModelFeatures.Hand.ANY,SceneModelFeatures.View.ANY,SceneModelFeatures.ItemState.ANY,"");
    var blockTag=new SceneModelFeatures.Match(List.of(),List.of(),List.of("stairs"),SceneModelFeatures.Hand.ANY,SceneModelFeatures.View.ANY,SceneModelFeatures.ItemState.ANY,"",List.of("minecraft:oak_stairs"),List.of("example:wood"));
    var ctx=new SceneModelFeatures.ItemContext("minecraft:oak_stairs",Set.of(),Set.of("block","stairs"),false,true,false,SceneModelFeatures.ItemState.IDLE,"NONE","minecraft:oak_stairs",Set.of("example:wood"));
    assertFalse(itemTag.matches(ctx));assertTrue(blockTag.matches(ctx));
    assertFalse(blockTag.matches(new SceneModelFeatures.ItemContext("minecraft:oak_stairs",Set.of("example:wood"),Set.of("stairs"),false,true,false,SceneModelFeatures.ItemState.IDLE,"NONE")));
  }
  @Test void independentFaceClipPreservesBodySourceAndReleasesAtSameTime() throws Exception {
    var a=asset();var p=profile(a);try(var player=scenes.playback(a,ScenePackagePlayback.Selection.REST,ScenePackagePlayback.Settings.preview().withPhysics(false).withModelProfile(p),ReadLimits.DEFAULT)) {
      var before=player.seek(.5);player.expressionAnimation("smile",.75);var after=player.seek(.5);
      assertEquals(ScenePackagePlayback.Selection.REST,player.selection());assertEquals(((ScenePackagePlayback.Mmd)before.details()).value().pose().bones(),((ScenePackagePlayback.Mmd)after.details()).value().pose().bones());
      assertEquals(.45,((ScenePackagePlayback.Mmd)after.details()).value().pose().morphs().meshWeights().get(0),1e-5);
      player.expressionAnimation(null,0);assertEquals(before,player.seek(.5));
    }
  }
  @Test void liveControllerLayerOwnsOnlyItsBoneMaskAndBlendsBeforeDeformation() throws Exception {
    var a=asset();var p=profile(a);try(var player=scenes.playback(a,new ScenePackagePlayback.Selection("@ysm/profile/smile",-1),ScenePackagePlayback.Settings.preview().withPhysics(false).withModelProfile(p),ReadLimits.DEFAULT)){
      var baseline=player.seek(.5);var wanted=new Pose(new Vec3(1,0,0),Rotation.axisAngle(new Vec3(0,0,1),.6));player.sourcePoses(Map.of(0,wanted));
      player.layers(List.of(new ScenePackagePlayback.Layer(new ScenePackagePlayback.Selection("@ysm/controller",-1),0,.5f,Set.of(0),Set.of())));var frame=player.seek(.5);
      var before=((ScenePackagePlayback.Mmd)baseline.details()).value().pose();var after=((ScenePackagePlayback.Mmd)frame.details()).value().pose();
      assertEquals(before.bones().get(0).position().x()+.5,after.bones().get(0).position().x(),1e-5);assertEquals(before.morphs(),after.morphs());
      assertEquals(.5f,frame.referenceWeights().get(0));
      player.layers(List.of(new ScenePackagePlayback.Layer(new ScenePackagePlayback.Selection("@ysm/controller",-1),0,1,Set.of(0),Set.of())));
      assertEquals(1f,player.seek(.5).referenceWeights().get(0));
      player.layers(List.of());assertEquals(baseline,player.seek(.5));
    }
  }
  @Test void sourceSwitchRestoresOffsetWithoutRestartingSimulationClock()throws Exception{
    var a=asset();var p=profile(a);try(var player=scenes.playback(a,ScenePackagePlayback.Selection.REST,ScenePackagePlayback.Settings.preview().withPhysics(false).withModelProfile(p),ReadLimits.DEFAULT)){
      player.seek(5);player.bonePoses(Map.of());player.expressions(Map.of("happy",0f));player.expressions(Map.of());
      assertTrue(player.select(new ScenePackagePlayback.Selection("@ysm/profile/smile",-1),new AnimationPlaybackRange(0,1,true),.5));var frame=player.seek(5);
      assertEquals(5,frame.seconds());assertEquals(.3,((ScenePackagePlayback.Mmd)frame.details()).value().pose().morphs().meshWeights().get(0),1e-5);
    }
  }
  @Test void firstPersonBoneFilterDoesNotRemoveThirdPersonGeometry() throws Exception {
    var a=asset();var p=profile(a).withBones(Map.of("bone:hidden",SceneSkeleton.bind(SceneSkeleton.of(a),0)));
    var f=p.features();p=p.withFeatures(new SceneModelFeatures(f.expressions(),f.animations(),List.of(),List.of(),f.socketSpace(),f.procedural(),new SceneModelFeatures.FirstPerson(List.of(),"",0,0,0,List.of("bone:hidden"),.5f)));
    try(var player=scenes.playback(a,ScenePackagePlayback.Selection.REST,ScenePackagePlayback.Settings.preview().withPhysics(false).withModelProfile(p),ReadLimits.DEFAULT)) {
      var frame=player.seek(0);int third=frame.thirdPerson().draws().stream().flatMap(d->d.geometry().primitives().stream()).mapToInt(t->t.indices().size()).sum();
      int first=frame.firstPerson().draws().stream().flatMap(d->d.geometry().primitives().stream()).mapToInt(t->t.indices().size()).sum();assertTrue(first<third);assertTrue(third>0);
      assertEquals(third,player.seek(1).thirdPerson().draws().stream().flatMap(d->d.geometry().primitives().stream()).mapToInt(t->t.indices().size()).sum());
    }
  }
  @Test void faceFadeReturnsToTheMovingSourceRatherThanClearingAllMorphs() throws Exception {
    var a=asset();var p=profile(a);var f=p.features();var e=f.expressions().get("happy");
    var slow=new SceneModelFeatures.Expression(e.targets(),e.blocks(),.5,.5);
    p=p.withFeatures(new SceneModelFeatures(Map.of("happy",slow,"blink",e),f.animations(),List.of(),List.of(),f.socketSpace(),f.procedural(),f.firstPerson()));
    try(var player=scenes.playback(a,new ScenePackagePlayback.Selection("@ysm/profile/smile",-1),ScenePackagePlayback.Settings.preview().withPhysics(false).withModelProfile(p),ReadLimits.DEFAULT)) {
      player.seek(.5);player.expressions(Map.of("happy",1f));assertEquals(.3,((ScenePackagePlayback.Mmd)player.seek(.5).details()).value().pose().morphs().meshWeights().get(0),1e-5);
      assertEquals(.725,((ScenePackagePlayback.Mmd)player.seek(.75).details()).value().pose().morphs().meshWeights().get(0),1e-5);
      player.seek(1);player.expressions(Map.of());assertEquals(.8,((ScenePackagePlayback.Mmd)player.seek(1.25).details()).value().pose().morphs().meshWeights().get(0),1e-5);
      assertEquals(.6,((ScenePackagePlayback.Mmd)player.seek(1.5).details()).value().pose().morphs().meshWeights().get(0),1e-5);
    }
  }
}
