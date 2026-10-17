package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.vrm.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VrmHostPlaybackTest {
  final SceneProvider services=YsmRuntime.scenes();
  ScenePackageAssets asset(boolean legacy) {return asset(legacy,true);}
  ScenePackageAssets asset(boolean legacy,boolean scaled) {
    var nodes=List.of(new SceneAsset.Node("parent",new IntData(1),new Transform(new Vec3(0,2,0),Rotation.axisAngle(new Vec3(0,0,1),.3),scaled?new Vec3(2,3,4):Vec3.ONE),null,-1,-1,-1,-1,FloatData.EMPTY,Map.of()),
      new SceneAsset.Node("unusualHeadName",IntData.EMPTY,new Transform(new Vec3(0,1,0),Rotation.axisAngle(new Vec3(1,0,0),.4),Vec3.ONE),null,-1,-1,-1,-1,FloatData.EMPTY,Map.of()));
    var coverage=new CompatibilityReport(List.of(),List.of());
    var scene=new SceneAsset("avatar",SceneAsset.Coordinates.GLTF,nodes,List.of(new SceneAsset.Scene("",new IntData(0),Map.of())),0,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),coverage);
    var avatar=new VrmDocument(new GltfDocument(scene,ByteData.EMPTY,Map.of()),legacy?VrmDocument.Version.VRM_0:VrmDocument.Version.VRM_1,Map.of("hips",0,"head",1),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),null,null,List.of(),coverage);
    var source=new ScenePackage(new ScenePackage.Source("model","avatar.vrm",ScenePackage.Format.VRM),new ScenePackage.Settings(1,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),List.of(),List.of(),Map.of("avatar.vrm",ByteData.EMPTY));
    return new ScenePackageAssets(source,Map.of("model",new ScenePackageAssets.Vrm(avatar)),Map.of());
  }
  SceneModelProfile profile(ScenePackageAssets a){return SceneProfiles.defaults(a,3,0).withRetarget(new SceneModelProfile.Retarget(Map.of("head","Head"),1));}
  ScenePackagePlayback play(ScenePackageAssets a,SceneModelProfile p)throws Exception{return services.playback(a,ScenePackagePlayback.Selection.REST,ScenePackagePlayback.Settings.preview().withPhysics(false).withModelProfile(p),ReadLimits.DEFAULT);}
  static VrmEvaluation.Frame vrm(ScenePackagePlayback.Frame f){return ((ScenePackagePlayback.Vrm)f.details()).value();}
  static void vector(Vec3 a,Vec3 b){assertEquals(a.x(),b.x(),2e-5);assertEquals(a.y(),b.y(),2e-5);assertEquals(a.z(),b.z(),2e-5);}
  @Test void referenceSocketsRemoveNonidentityVrmRestAxes()throws Exception {
    var a=asset(false,false);var p=profile(a);var bones=new HashMap<>(p.bones());bones.put("head",bones.get("head").withReference(20,30,40));p=p.withBones(bones);
    var f=p.features();p=p.withFeatures(new SceneModelFeatures(f.expressions(),f.animations(),f.itemRules(),f.layers(),SceneModelFeatures.SocketSpace.RETARGET_REFERENCE,f.procedural(),f.firstPerson()));
    var driver=new YsmSkeletonBinding(List.of(new YsmSkeletonBinding.Bone("Head",-1,Vec3.ZERO,Vec3.ZERO)),a,p);
    float[] attributes=new float[14];attributes[6]=attributes[7]=attributes[8]=1;attributes[1]=.6f;
    try(var player=play(a,p)){
      player.bonePoses(driver.evaluate(attributes));var frame=player.seek(0);
      var socket=new SceneModelProfile.HandSocket(true,"head",0,0,0,0,0,0,1);
      var matrix=SceneHandSockets.resolve(frame,p,socket,SceneHandSockets.restAxes(SceneFeatureBindings.scene(a)).get(1)).orElseThrow();
      var expected=Rotation.axisAngle(new Vec3(0,1,0),.6);
      for(var axis:List.of(new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1)))vector(expected.rotate(axis),matrix.transformDirection(axis));
      vector(SceneRig.position(vrm(frame).pose().globalMatrices().get(1)),matrix.transformPoint(Vec3.ZERO));
    }
  }
  @Test void nativeViewpointDefaultsPersistVersionSpecificBoneLocalOffsets()throws Exception {
    for(boolean legacy:List.of(false,true)){
      var a=asset(legacy);var old=((ScenePackageAssets.Vrm)a.model()).value();var offset=new Vec3(.03f,.06f,.09f);var range=new VrmDocument.RangeMap(90,10,FloatData.EMPTY);
      // A legacy viewpoint can explicitly target a node outside humanBones.
      var doc=new VrmDocument(old.gltf(),old.version(),Map.of("head",1),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
          new VrmDocument.FirstPerson(0,offset,List.of()),new VrmDocument.LookAt("bone",offset,range,range,range,range),List.of(),old.compatibility());
      a=new ScenePackageAssets(a.source(),Map.of("model",new ScenePackageAssets.Vrm(doc)),Map.of());var p=SceneProfiles.defaults(a,3,0);var fp=p.features().firstPerson();
      assertEquals(legacy?0:1,p.bones().get(fp.originBinding()).index());assertEquals(.03,fp.originX(),1e-6);assertEquals(.06,fp.originY(),1e-6);assertEquals(legacy?-.09:.09,fp.originZ(),1e-6);
      assertEquals(p,services.readModelProfile(services.writeModelProfile(p)));SceneFeatureBindings.validate(a,p);
    }
  }
  @Test void boneLocalViewpointUsesFinalRotationScaleAndIgnoresItemCalibration()throws Exception {
    var a=asset(false);var p=profile(a);var bones=new HashMap<>(p.bones());bones.put("head",bones.get("head").withReference(20,30,40));p=p.withBones(bones);
    var f=p.features();p=p.withFeatures(new SceneModelFeatures(f.expressions(),f.animations(),f.itemRules(),f.layers(),SceneModelFeatures.SocketSpace.RETARGET_REFERENCE,f.procedural(),f.firstPerson()));
    try(var player=play(a,p)){
      player.bonePoses(Map.of(1,new Pose(Vec3.ZERO,Rotation.axisAngle(new Vec3(1,0,0),.7))));var frame=player.seek(0);var local=new Vec3(.1f,.2f,.3f);
      var expected=SceneHandSockets.coordinates(frame.thirdPerson().coordinates()).multiply(vrm(frame).pose().globalMatrices().get(1)).transformPoint(local);
      vector(expected,SceneHandSockets.resolveBonePoint(frame,p,"head",local).orElseThrow());
      assertNotEquals(expected,SceneRig.position(vrm(frame).pose().globalMatrices().get(1)).add(local));
    }
  }
  @Test void semanticDefaultsAndScaledRestRigDoNotDependOnBoneNames() {
    var a=asset(false);var p=profile(a);assertEquals(1,p.bones().get("head").index());assertEquals("unusualHeadName",p.bones().get("head").name());
    var rig=new SceneRig(SceneFeatureBindings.scene(a));vector(SceneRig.position(rig.restGlobal().get(1)),SceneSkeleton.of(a).get(1).position());
    assertEquals(0,p.placement().yaw());assertEquals(180,profile(asset(true)).placement().yaw());
    assertEquals(p.profileId(),profile(a).profileId());
  }
  @Test void ysmWorldMotionIsNotMultipliedByTargetParentScale()throws Exception {
    for(boolean legacy:List.of(false,true)){
      var a=asset(legacy);var p=profile(a);var binding=new YsmSkeletonBinding(List.of(new YsmSkeletonBinding.Bone("Head",-1,Vec3.ZERO,Vec3.ZERO)),a,p);
      float[] attributes=new float[14];attributes[6]=attributes[7]=attributes[8]=1;
      try(var player=play(a,p)){
        var nativeRest=vrm(player.seek(0));
        player.bonePoses(binding.evaluate(attributes));var rest=vrm(player.seek(0));
        attributes[3]=-1;attributes[4]=2;attributes[5]=3;attributes[1]=.6f;
        player.bonePoses(binding.evaluate(attributes));var animated=vrm(player.seek(0));
        var displacement=SceneRig.position(animated.pose().globalMatrices().get(1)).subtract(SceneRig.position(rest.pose().globalMatrices().get(1)));
        vector(new Vec3(legacy?1:-1,2,legacy?3:-3),displacement);
        var expected=Rotation.axisAngle(new Vec3(0,1,0),.6).multiply(SceneRig.rotation(SceneFeatureBindings.scene(a).nodes().get(0).localMatrix())).multiply(SceneRig.rotation(SceneFeatureBindings.scene(a).nodes().get(1).localMatrix()));
        var actual=animated.localRotations().get(0).multiply(animated.localRotations().get(1));vector(expected.rotate(new Vec3(0,0,1)),actual.rotate(new Vec3(0,0,1)));
        assertThrows(IllegalArgumentException.class,()->player.boneRotations(Map.of(99,Rotation.IDENTITY)));assertEquals(animated,vrm(player.seek(0)));
        player.bonePoses(Map.of());assertEquals(nativeRest,vrm(player.seek(0)));
      }
    }
  }
  @Test void controllerLayersBlendAndReleaseWithoutChangingBodySelection()throws Exception {
    var a=asset(false);var p=profile(a);try(var player=play(a,p)){
      var base=player.seek(2);var q=Rotation.axisAngle(new Vec3(0,0,1),.8);player.sourcePoses(Map.of(1,new Pose(new Vec3(2,0,0),q)));
      player.layers(List.of(new ScenePackagePlayback.Layer(new ScenePackagePlayback.Selection("@ysm/controller",-1),0,.5f,Set.of(1),Set.of())));
      var frame=player.seek(2);assertEquals(ScenePackagePlayback.Selection.REST,player.selection());assertEquals(.5f,frame.referenceWeights().get(1));
      var local=vrm(frame).pose().localMatrices().get(1);assertEquals(1,local.get(3,0),1e-5);
      var expected=vrm(base).localRotations().get(1).multiply(Rotation.slerp(Rotation.IDENTITY,q,.5));vector(expected.rotate(new Vec3(0,1,0)),vrm(frame).localRotations().get(1).rotate(new Vec3(0,1,0)));
      assertEquals(vrm(base).pose().localMatrices().get(0),vrm(frame).pose().localMatrices().get(0));
      player.layers(List.of());assertEquals(base,player.seek(2));
    }
  }
  @Test void sourceSwitchKeepsSimulationTimeAndFailedCandidateDoesNotReplaceSource()throws Exception {
    var a=asset(false);var p=profile(a);var f=p.features();
    var track=new SceneModelFeatures.Track(SceneModelFeatures.Target.BONE,"head",AnimationClip.Property.TRANSLATION,SceneModelFeatures.Interpolation.LINEAR,List.of(new SceneModelFeatures.Key(0,List.of(0f,1f,0f)),new SceneModelFeatures.Key(1,List.of(2f,1f,0f))));
    p=p.withFeatures(new SceneModelFeatures(f.expressions(),Map.of("move",new SceneModelFeatures.Clip(1,30,List.of(track))),f.itemRules(),f.layers(),f.socketSpace(),f.procedural(),f.firstPerson()));
    try(var player=play(a,p)){
      player.seek(5);var selected=new ScenePackagePlayback.Selection("@ysm/profile/move",-1);assertTrue(player.select(selected,new AnimationPlaybackRange(0,1,true),.5));
      var frame=player.seek(5);assertEquals(5,vrm(frame).pose().seconds());assertEquals(1,vrm(frame).pose().localMatrices().get(1).get(3,0),1e-5);
      assertThrows(IllegalArgumentException.class,()->player.select(new ScenePackagePlayback.Selection("missing",0),null));assertEquals(selected,player.selection());assertEquals(frame,player.seek(5));
      assertEquals(.5,vrm(player.seek(5.75)).pose().localMatrices().get(1).get(3,0),1e-5);
    }
  }
  private ScenePackageAssets motionAsset(boolean blink,boolean spring)throws Exception{
    var fixture=new ScenePackagePlaybackTest();
    String avatar=new String(fixture.resource("/three-vrm-oracle/retarget-avatar.gltf").copy(),java.nio.charset.StandardCharsets.UTF_8);
    String motion=new String(fixture.resource("/three-vrm-oracle/retarget-source.gltf").copy(),java.nio.charset.StandardCharsets.UTF_8);
    if(blink){avatar=avatar.replace("happy","blink");motion=motion.replace("happy","blink");}
    if(spring){var json=com.google.gson.JsonParser.parseString(avatar).getAsJsonObject();var nodes=json.getAsJsonArray("nodes");int n=nodes.size();
      var root=nodes.get(0).getAsJsonObject();if(!root.has("children"))root.add("children",new com.google.gson.JsonArray());root.getAsJsonArray("children").add(n);
      nodes.add(com.google.gson.JsonParser.parseString("{\"translation\":[0,1,0],\"children\":["+(n+1)+"]}"));nodes.add(com.google.gson.JsonParser.parseString("{\"translation\":[0,-0.6,0]}"));
      json.getAsJsonObject("extensions").add("VRMC_springBone",com.google.gson.JsonParser.parseString("{\"specVersion\":\"1.0\",\"springs\":[{\"joints\":[{\"node\":"+n+",\"gravityDir\":[1,0,0],\"gravityPower\":0.5,\"stiffness\":0.7},{\"node\":"+(n+1)+"}]}]}"));avatar=json.toString();}
    return fixture.asset(ScenePackage.Format.VRM,"avatar.gltf",List.of(new ScenePackage.Source("motion","motion.gltf",ScenePackage.Format.VRMA)),Map.of(
      "avatar.gltf",new ByteData(avatar.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"motion.gltf",new ByteData(motion.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
  }
  @Test void vrmaMaskedLayersComposeInOrderAgainstBodyAndRelease()throws Exception{
    var assets=motionAsset(false,false);var profile=SceneProfiles.defaults(assets,2,0);var f=profile.features();
    var face=new SceneModelFeatures.Track(SceneModelFeatures.Target.EXPRESSION,"happy",AnimationClip.Property.MORPH_WEIGHTS,SceneModelFeatures.Interpolation.LINEAR,List.of(new SceneModelFeatures.Key(0,List.of(.8f))));
    profile=profile.withFeatures(new SceneModelFeatures(f.expressions(),Map.of("face",new SceneModelFeatures.Clip(1,30,List.of(face))),f.itemRules(),f.layers(),f.socketSpace(),f.procedural(),f.firstPerson()));
    var motion=new ScenePackagePlayback.Selection("motion",0);int head=profile.bones().get("head").index();
    try(var player=services.playback(assets,new ScenePackagePlayback.Selection("@ysm/profile/face",-1),ScenePackagePlayback.Settings.preview().withPhysics(false).withModelProfile(profile),ReadLimits.DEFAULT)){
      var base=player.seek(1);var layers=List.of(new ScenePackagePlayback.Layer(motion,1,.5f,Set.of(head),Set.of(),Set.of("happy")));
      player.layers(layers);var mixed=vrm(player.seek(1));assertEquals(.9,mixed.expressions().get("happy"),1e-6);
      var expected=services.retarget(((ScenePackageAssets.Vrma)assets.documents().get("motion")).value(),((ScenePackageAssets.Vrm)assets.model()).value(),VrmaEvaluation.Settings.automatic()).sample(0,1);
      var q=Rotation.slerp(vrm(base).localRotations().get(head),expected.rotations().get(head),.5);
      vector(q.rotate(Vec3.ONE),mixed.localRotations().get(head).rotate(Vec3.ONE));
      for(int n=0;n<mixed.pose().localMatrices().size();n++)if(n!=head)assertEquals(vrm(base).pose().localMatrices().get(n),mixed.pose().localMatrices().get(n));
      player.sourcePoses(Map.of(head,new Pose(Vec3.ZERO,Rotation.IDENTITY)));
      var both=new ArrayList<>(layers);both.add(new ScenePackagePlayback.Layer(new ScenePackagePlayback.Selection("@ysm/controller",-1),0,1,Set.of(head),Set.of()));player.layers(both);
      vector(new SceneRig(((ScenePackageAssets.Vrm)assets.model()).value().scene()).restRotations().get(head).rotate(Vec3.ONE),vrm(player.seek(1)).localRotations().get(head).rotate(Vec3.ONE));
      player.layers(List.of());assertEquals(base,player.seek(1));
    }
  }
  @Test void nativeVrmaBlinkOwnsItsChannelEvenDuringProceduralBlink()throws Exception{
    var a=motionAsset(true,false);var p=SceneProfiles.defaults(a,2,0);var f=p.features();
    p=p.withFeatures(new SceneModelFeatures(f.expressions(),f.animations(),f.itemRules(),f.layers(),f.socketSpace(),new SceneModelFeatures.Procedural(true,"blink",.2,.15,false,"","",20),f.firstPerson()));
    try(var player=services.playback(a,new ScenePackagePlayback.Selection("motion",0),ScenePackagePlayback.Settings.preview().withPhysics(false).withModelProfile(p),ReadLimits.DEFAULT)){
      var oracle=services.retarget(((ScenePackageAssets.Vrma)a.documents().get("motion")).value(),((ScenePackageAssets.Vrm)a.model()).value(),VrmaEvaluation.Settings.automatic());
      for(int i=1;i<=40;i++){double t=i/40d;assertEquals(oracle.sample(0,t).input().expressions().get("blink"),vrm(player.seek(t)).expressions().get("blink"),1e-6);}
    }
  }
  @Test void sourceSwitchKeepsAccumulatedSpringState()throws Exception{
    var a=motionAsset(false,true);var p=SceneProfiles.defaults(a,2,0);var settings=ScenePackagePlayback.Settings.preview().withModelProfile(p);
    try(var player=services.playback(a,ScenePackagePlayback.Selection.REST,settings,ReadLimits.DEFAULT);var control=services.playback(a,ScenePackagePlayback.Selection.REST,settings,ReadLimits.DEFAULT)){
      var before=vrm(player.seek(.5));control.seek(.5);assertTrue(player.select(ScenePackagePlayback.Selection.REST,null));
      assertEquals(before.pose().globalMatrices(),vrm(player.seek(.5)).pose().globalMatrices());
      assertEquals(vrm(control.seek(.75)).pose().globalMatrices(),vrm(player.seek(.75)).pose().globalMatrices());
      assertThrows(IllegalArgumentException.class,()->player.select(new ScenePackagePlayback.Selection("motion",99),null));
      assertEquals(vrm(control.seek(1)).pose().globalMatrices(),vrm(player.seek(1)).pose().globalMatrices());
    }
  }

  @Test void nativeMetadataThumbnailIndexDomainsAndProfileRoundtrip()throws Exception{
    for(boolean legacy:List.of(false,true)){
      var source=asset(legacy);var avatar=((ScenePackageAssets.Vrm)source.model()).value();var scene=avatar.scene();
      var images=List.of(new SceneAsset.Image("unused","image/png","",new ByteData(new byte[]{1}),Map.of()),new SceneAsset.Image("thumbnail","image/png","",new ByteData(new byte[]{2}),Map.of()));
      String meta=legacy?"{\"VRM\":{\"meta\":{\"title\":\"Avatar\",\"author\":\"Creator\",\"licenseName\":\"CC_BY\",\"texture\":0}}}":"{\"VRMC_vrm\":{\"meta\":{\"name\":\"Avatar\",\"authors\":[\"Creator\"],\"licenseUrl\":\"https://vrm.dev/licenses/1.0/\",\"thumbnailImage\":1}}}";
      var enriched=new SceneAsset(scene.name(),scene.coordinates(),scene.nodes(),scene.scenes(),scene.defaultScene(),scene.meshes(),scene.skins(),scene.materials(),List.of(new SceneAsset.Texture("",1,9729,9729,10497,10497,Map.of())),images,scene.cameras(),scene.lights(),scene.animations(),Map.of("extensions",meta),scene.compatibility());
      var document=new VrmDocument(new GltfDocument(enriched,ByteData.EMPTY,Map.of()),avatar.version(),avatar.humanBones(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),null,null,List.of(),avatar.compatibility());
      assertEquals(images.get(1),SceneProfiles.thumbnail(document).orElseThrow());assertEquals("Avatar",SceneProfiles.metadata(document).name());assertEquals("Creator",SceneProfiles.metadata(document).authors().get(0).name());
      var profile=profile(source).withMetadata(SceneProfiles.metadata(document));assertEquals(profile,services.readModelProfile(services.writeModelProfile(profile)));
    }
  }

}
