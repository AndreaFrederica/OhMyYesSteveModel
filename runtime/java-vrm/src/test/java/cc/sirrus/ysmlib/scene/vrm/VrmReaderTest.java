package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmFixtures.*;

class VrmReaderTest {
  @Test void readsVrm1ProfileAndRetainsUninterpretedMetadata() throws Exception {
    var json=avatar(false);int mesh=json.getAsJsonArray("nodes").size()-1;
    extension(json,false).add("expressions",JsonParser.parseString("{\"preset\":{\"happy\":{\"morphTargetBinds\":[{\"node\":"+mesh+",\"index\":2,\"weight\":0.75}]}},\"custom\":{\"wink\":{\"isBinary\":true}}}"));
    extension(json,false).add("lookAt",JsonParser.parseString("{\"type\":\"expression\",\"rangeMapHorizontalOuter\":{\"inputMaxValue\":0,\"outputScale\":1}}"));
    var result=read(json);assertEquals(VrmDocument.Version.VRM_1,result.version());assertEquals(2,result.expressions().size());
    assertEquals(.75,result.expressions().get(0).morphs().get(0).weight());assertTrue(result.scene().metadata().get("extensions").contains("customPolicy"));
    assertEquals(0,result.lookAt().horizontalOuter().inputMax());assertFalse(result.compatibility().canEvaluateRequiredFeatures());
    assertArrayEquals(json.toString().getBytes(StandardCharsets.UTF_8),result.gltf().source().copy());
  }
  @Test void legacyMeshBindingsExpandToInstancesAndKeepMaterialAndCurveFields() throws Exception {
    var json=avatar(true);var ext=extension(json,true);
    ext.add("blendShapeMaster",JsonParser.parseString("""
      {"blendShapeGroups":[{"name":"Smile","presetName":"joy","binds":[{"mesh":0,"index":1,"weight":45}],
       "materialValues":[{"materialName":"face","propertyName":"_Cutoff","targetValue":[0.8]}]}]}
      """));
    ext.add("materialProperties",JsonParser.parseString("""
      [{"name":"face","shader":"VRM/MToon","renderQueue":2450,"floatProperties":{"_Cutoff":0.3},
       "vectorProperties":{"_ShadeColor":[0.4,0.5,0.6,1]},"keywordMap":{"_ALPHATEST_ON":true},"tagMap":{"RenderType":"TransparentCutout"}}]
      """));
    ext.add("firstPerson",JsonParser.parseString("{\"lookAtTypeName\":\"BlendShape\",\"lookAtHorizontalOuter\":{\"curve\":[0,0,0,0,1,1,0,0],\"xRange\":60,\"yRange\":1},\"meshAnnotations\":[{\"mesh\":0,\"firstPersonFlag\":\"Both\"}]}"));
    var d=read(json);assertEquals(2,d.expressions().get(0).morphs().size());assertEquals(.45,d.expressions().get(0).morphs().get(0).weight(),1e-6);
    assertEquals(8,d.lookAt().horizontalOuter().curve().size());assertEquals(2,d.firstPerson().annotations().size());assertEquals(2450,d.legacyMaterials().get(0).renderQueue());
    var frame=new VrmExpressionEvaluator(d).evaluate(java.util.Map.of("joy",.5f));assertEquals(.55,frame.materials().get(0).parameters().get("_Cutoff").get(0),1e-6);
  }
  @Test void rejectsInvalidBindingsVersionsAliasesAndReadBudgets() throws Exception {
    var json=avatar(false);var ext=extension(json,false);ext.addProperty("specVersion","1.1");assertThrows(AssetFormatException.class,()->read(json));
    ext.addProperty("specVersion","1.0");ext.getAsJsonObject("humanoid").getAsJsonObject("humanBones").getAsJsonObject("head").addProperty("node",0);
    assertThrows(AssetFormatException.class,()->read(json));
    var valid=avatar(false);extension(valid,false).add("expressions",JsonParser.parseString("{\"preset\":{\"happy\":{\"morphTargetBinds\":[{\"node\":0,\"index\":0,\"weight\":1}]}}}"));
    assertThrows(AssetFormatException.class,()->read(valid));
    var bytes=new ByteData(avatar(false).toString().getBytes(StandardCharsets.UTF_8));
    assertThrows(AssetFormatException.class,()->new VrmReader().read(bytes,AssetResolver.NONE,new ReadLimits(64,10,64)));
  }
  @Test void springChainsAllowInterveningNodesButRejectOverlapAndInvalidAncestry() throws Exception {
    var json=avatar(false);json.getAsJsonObject("extensions").add("VRMC_springBone",JsonParser.parseString("""
      {"specVersion":"1.0","colliders":[{"node":0,"shape":{"capsule":{"offset":[0,0,0],"tail":[0,1,0],"radius":0.2}}}],
      "colliderGroups":[{"colliders":[0]}],"springs":[{"joints":[{"node":1},{"node":3}],"colliderGroups":[0]}]}
      """));
    var d=read(json);assertEquals(2,d.springs().get(0).joints().size());assertTrue(d.colliders().get(0).capsule());
    var springs=json.getAsJsonObject("extensions").getAsJsonObject("VRMC_springBone").getAsJsonArray("springs");
    springs.add(JsonParser.parseString("{\"joints\":[{\"node\":2}]}"));assertThrows(AssetFormatException.class,()->read(json));
    springs.remove(1);springs.get(0).getAsJsonObject().getAsJsonArray("joints").get(1).getAsJsonObject().addProperty("node",0);
    assertThrows(AssetFormatException.class,()->read(json));
  }
}
