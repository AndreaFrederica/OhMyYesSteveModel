package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmFixtures.*;

class VrmaTest {
  @Test void matchesOriginalVrmAndVrmaLoadersAcross61RetargetedFrames() throws Exception {
    var avatar=read(resource("retarget-avatar.gltf").getAsJsonObject());var motion=readMotion(resource("retarget-source.gltf").getAsJsonObject());
    var evaluator=new VrmaRetarget(motion,avatar,VrmaEvaluation.Settings.automatic());
    for(var value:resource("retarget.json").getAsJsonArray()) {
      var expected=value.getAsJsonObject();double seconds=expected.get("seconds").getAsDouble();var actual=evaluator.evaluate(seconds);
      assertEquals(expected.get("happy").getAsDouble(),actual.expressions().get("happy"),2e-6);
      for(var bone:expected.getAsJsonObject("bones").entrySet()) {
        int n=avatar.humanBones().get(bone.getKey());var matrix=bone.getValue().getAsJsonObject().getAsJsonArray("global");float[] output=actual.pose().globalMatrices().get(n).copy();
        for(int k=0;k<16;k++) assertEquals(matrix.get(k).getAsDouble(),output[k],1e-5,"time "+seconds+" bone "+bone.getKey()+" matrix "+k);
      }
    }
  }
  private JsonObject motion() {
    var json=avatar(false);var avatar=extension(json,false);var ext=new JsonObject();ext.addProperty("specVersion","1.0");ext.add("humanoid",avatar.get("humanoid"));
    json.getAsJsonObject("extensions").remove("VRMC_vrm");json.getAsJsonObject("extensions").add("VRMC_vrm_animation",ext);
    json.add("extensionsUsed",JsonParser.parseString("[\"VRMC_vrm_animation\"]"));json.add("animations",new JsonArray());
    json.getAsJsonArray("nodes").get(0).getAsJsonObject().add("translation",JsonParser.parseString("[0,1,0]"));return json;
  }
  private int bone(JsonObject json,String name) { return json.getAsJsonObject("extensions").getAsJsonObject("VRMC_vrm_animation").getAsJsonObject("humanoid").getAsJsonObject("humanBones").getAsJsonObject(name).get("node").getAsInt(); }
  private JsonObject newClip(JsonObject json) {
    var clip=new JsonObject();clip.add("samplers",new JsonArray());clip.add("channels",new JsonArray());json.getAsJsonArray("animations").add(clip);return clip;
  }
  private int accessor(JsonObject json,float[] values,String type,int components) {
    var bytes=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float f:values) bytes.putFloat(f);
    var buffer=new JsonObject();buffer.addProperty("byteLength",bytes.capacity());buffer.addProperty("uri","data:application/octet-stream;base64,"+Base64.getEncoder().encodeToString(bytes.array()));
    int bufferIndex=json.getAsJsonArray("buffers").size();json.getAsJsonArray("buffers").add(buffer);
    var view=new JsonObject();view.addProperty("buffer",bufferIndex);view.addProperty("byteLength",bytes.capacity());int viewIndex=json.getAsJsonArray("bufferViews").size();json.getAsJsonArray("bufferViews").add(view);
    var a=new JsonObject();a.addProperty("bufferView",viewIndex);a.addProperty("componentType",5126);a.addProperty("count",values.length/components);a.addProperty("type",type);
    int index=json.getAsJsonArray("accessors").size();json.getAsJsonArray("accessors").add(a);return index;
  }
  private void track(JsonObject json,JsonObject clip,int node,String path,float[] values) {
    int components=path.equals("rotation")?4:3;var sampler=new JsonObject();sampler.addProperty("input",accessor(json,new float[]{0,1},"SCALAR",1));sampler.addProperty("output",accessor(json,values,components==4?"VEC4":"VEC3",components));
    sampler.addProperty("interpolation","LINEAR");int i=clip.getAsJsonArray("samplers").size();clip.getAsJsonArray("samplers").add(sampler);
    var channel=new JsonObject();channel.addProperty("sampler",i);var target=new JsonObject();target.addProperty("node",node);target.addProperty("path",path);channel.add("target",target);clip.getAsJsonArray("channels").add(channel);
  }
  private VrmaDocument readMotion(JsonObject json) throws Exception { return new VrmaReader().read(new ByteData(json.toString().getBytes(StandardCharsets.UTF_8)),AssetResolver.NONE,ReadLimits.DEFAULT); }
  @Test void readsMultipleClipsAndRejectsForbiddenHumanoidChannels() throws Exception {
    var json=motion();track(json,newClip(json),0,"translation",new float[]{0,1,0,1,1,0});track(json,newClip(json),0,"rotation",new float[]{0,0,0,1,0,1,0,0});
    assertEquals(2,readMotion(json).animations().size());
    var clip=newClip(json);track(json,clip,bone(json,"head"),"translation",new float[]{0,0,0,0,1,0});assertThrows(AssetFormatException.class,()->readMotion(json));
    clip.getAsJsonArray("channels").get(0).getAsJsonObject().getAsJsonObject("target").addProperty("path","scale");assertThrows(AssetFormatException.class,()->readMotion(json));
    json.getAsJsonArray("animations").remove(2);track(json,newClip(json),bone(json,"leftEye"),"rotation",new float[]{0,0,0,1,0,1,0,0});assertThrows(AssetFormatException.class,()->readMotion(json));
  }
  @Test void restPoseAxesHipsScaleAndDefaultClipAreRetargeted() throws Exception {
    var json=motion();int arm=bone(json,"leftUpperArm");var clip=newClip(json);track(json,clip,0,"translation",new float[]{0,1,0,1,1,0});
    var sourceRest=Rotation.axisAngle(new Vec3(0,0,1),.4);var sourceEnd=sourceRest.multiply(Rotation.axisAngle(new Vec3(1,0,0),.7));
    json.getAsJsonArray("nodes").get(arm).getAsJsonObject().add("rotation",array(sourceRest));track(json,clip,arm,"rotation",quaternions(sourceRest,sourceEnd));
    var targetJson=avatar(false);targetJson.getAsJsonArray("nodes").get(0).getAsJsonObject().add("translation",JsonParser.parseString("[0,2,0]"));
    var targetRest=Rotation.axisAngle(new Vec3(0,1,0),-.6);targetJson.getAsJsonArray("nodes").get(arm).getAsJsonObject().add("rotation",array(targetRest));
    var target=read(targetJson);var retarget=new VrmaRetarget(readMotion(json),target,VrmaEvaluation.Settings.automatic());var result=retarget.evaluate(1);
    assertEquals(2,result.pose().globalMatrices().get(0).get(3,0),1e-6);assertEquals(2,result.pose().globalMatrices().get(0).get(3,1),1e-6);
    var expected=sourceEnd.multiply(sourceRest.inverse()).multiply(targetRest);assertQuaternion(expected,result.localRotations().get(arm));
    assertTrue(retarget.compatibility().canEvaluateRequiredFeatures());
    assertThrows(IllegalArgumentException.class,()->retarget.evaluate(1,0));
  }
  @Test void sourceOnlyOptionalBoneMotionPropagatesToEachTargetBranch() throws Exception {
    var json=motion();int chest=bone(json,"chest");var clip=newClip(json);var end=Rotation.axisAngle(new Vec3(0,0,1),.6);track(json,clip,chest,"rotation",quaternions(Rotation.IDENTITY,end));
    var targetJson=avatar(false);var targetBones=extension(targetJson,false).getAsJsonObject("humanoid").getAsJsonObject("humanBones");targetBones.remove("chest");targetBones.remove("upperChest");
    var target=read(targetJson);var output=new VrmaRetarget(readMotion(json),target,VrmaEvaluation.Settings.automatic()).evaluate(1);
    assertQuaternion(end,output.localRotations().get(target.humanBones().get("neck")));assertQuaternion(end,output.localRotations().get(target.humanBones().get("leftShoulder")));assertQuaternion(end,output.localRotations().get(target.humanBones().get("rightShoulder")));
    assertQuaternion(Rotation.IDENTITY,output.localRotations().get(target.humanBones().get("head")));
  }
  @Test void vrm0FacingPresetMappingAndOutOfRangeWeightsArePreserved() throws Exception {
    var json=motion();var nodes=json.getAsJsonArray("nodes");int expression=nodes.size();nodes.add(JsonParser.parseString("{\"translation\":[0,0,0]}"));
    var ext=json.getAsJsonObject("extensions").getAsJsonObject("VRMC_vrm_animation");ext.add("expressions",JsonParser.parseString("{\"preset\":{\"happy\":{\"node\":"+expression+"}}}"));
    var clip=newClip(json);track(json,clip,0,"translation",new float[]{0,1,0,1,1,2});track(json,clip,expression,"translation",new float[]{-1,0,0,2,0,0});
    var targetJson=avatar(true);targetJson.getAsJsonArray("nodes").get(0).getAsJsonObject().add("translation",JsonParser.parseString("[0,2,0]"));
    extension(targetJson,true).add("blendShapeMaster",JsonParser.parseString("{\"blendShapeGroups\":[{\"name\":\"smile\",\"presetName\":\"joy\"}]}"));
    var evaluator=new VrmaRetarget(readMotion(json),read(targetJson),VrmaEvaluation.Settings.automatic());
    assertEquals(0,evaluator.evaluate(0).expressions().get("joy"));var frame=evaluator.evaluate(1);assertEquals(1,frame.expressions().get("joy"));
    var position=VrmMath.position(frame.pose().globalMatrices().get(0));assertEquals(-2,position.x(),1e-6);assertEquals(-4,position.z(),1e-6);
  }
  @Test void gazeUsesExtrinsicZxyAndReportsUnmappedSourceExpressions() throws Exception {
    var q=Rotation.axisAngle(new Vec3(0,1,0),.4).multiply(Rotation.axisAngle(new Vec3(1,0,0),.3)).multiply(Rotation.axisAngle(new Vec3(0,0,1),.2));
    var angles=VrmaRetarget.angles(q);assertEquals(Math.toDegrees(.4),angles.yaw(),1e-5);assertEquals(Math.toDegrees(.3),angles.pitch(),1e-5);
    var json=motion();int node=json.getAsJsonArray("nodes").size();json.getAsJsonArray("nodes").add(new JsonObject());
    var ext=json.getAsJsonObject("extensions").getAsJsonObject("VRMC_vrm_animation");ext.add("expressions",JsonParser.parseString("{\"custom\":{\"missing\":{\"node\":"+node+"}}}"));
    track(json,newClip(json),node,"translation",new float[]{0,0,0,1,0,0});
    var retarget=new VrmaRetarget(readMotion(json),read(avatar(false)),VrmaEvaluation.Settings.automatic());assertFalse(retarget.compatibility().canEvaluateRequiredFeatures());assertFalse(retarget.compatibility().diagnostics().isEmpty());
    assertThrows(IllegalArgumentException.class,()->new VrmaRetarget(readMotion(json),read(avatar(false)),new VrmaEvaluation.Settings(null,Map.of("missing","absent"))));
  }
  private JsonArray array(Rotation q) { var a=new JsonArray();a.add(q.x());a.add(q.y());a.add(q.z());a.add(q.w());return a; }
  private float[] quaternions(Rotation a,Rotation b) { return new float[]{a.x(),a.y(),a.z(),a.w(),b.x(),b.y(),b.z(),b.w()}; }
  private void assertQuaternion(Rotation a,Rotation b) { assertEquals(1,Math.abs((double)a.x()*b.x()+(double)a.y()*b.y()+(double)a.z()*b.z()+(double)a.w()*b.w()),2e-6); }
}
