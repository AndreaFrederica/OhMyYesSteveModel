package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmFixtures.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmMaterialDefaults.MTOON;

class VrmMaterialsTest {
  @Test void matchesOriginalMtoonLoaderParametersAndFourRenderModes() throws Exception {
    var document=read(resource("materials-avatar.gltf").getAsJsonObject());
    var actual=new VrmMaterialEvaluator(document).evaluate(null,0).materials();var expected=resource("materials.json").getAsJsonArray();
    for(int i=0;i<actual.size();i++) {
      var m=actual.get(i);var reference=expected.get(i).getAsJsonObject();assertEquals(VrmMaterials.Shader.MTOON_1,m.shader());
      var p=m.parameters();for(var e:reference.entrySet()) {
        String key=e.getKey();var value=e.getValue();
        if(key.equals("outlineWidthMode")) { assertEquals(value.getAsString(),m.renderState().outlineMode());continue; }
        if(value.isJsonArray()) { var a=value.getAsJsonArray();var v=p.get(Set.of("baseColor","emissive").contains(key)?key:MTOON+key);
          assertEquals(a.size(),v.size());for(int j=0;j<a.size();j++) assertEquals(a.get(j).getAsFloat(),v.get(j),1e-6);
        } else if(p.containsKey(MTOON+key)) assertEquals(value.getAsFloat(),p.get(MTOON+key).get(0),1e-6);
      }
      assertEquals(reference.get("transparent").getAsBoolean(),m.renderState().alphaMode().equals("BLEND"));
      assertEquals(reference.get("depthWrite").getAsBoolean(),m.renderState().depthWrite());
      assertEquals(reference.get("side").getAsInt()==2?VrmMaterials.Cull.NONE:VrmMaterials.Cull.BACK,m.renderState().cull());
      if(m.renderState().alphaMode().equals("MASK")) assertEquals(reference.get("alphaTest").getAsFloat(),m.renderState().alphaCutoff());
      assertEquals(i,m.renderState().category());
    }
  }
  @Test void keepsLegacyShaderParametersQueuesTextureTransferAndExpressionDefaults() throws Exception {
    var json=legacy();var d=read(json);var expression=new VrmExpressionEvaluator(d).evaluate(Map.of("joy",.5f));
    var evaluator=new VrmMaterialEvaluator(d);var m=evaluator.evaluate(expression.materials(),.5).materials().get(0);
    assertEquals(VrmMaterials.Shader.MTOON_0,m.shader());assertEquals(VrmMaterials.ParameterSpace.LEGACY_SHADER,m.parameterSpace());
    assertEquals(3077,m.renderState().legacyQueue());assertEquals(VrmMaterials.Cull.FRONT,m.renderState().cull());assertTrue(m.renderState().depthWrite());
    assertEquals("BLEND",m.renderState().alphaMode());assertEquals(5,m.renderState().sourceBlend());assertEquals(10,m.renderState().destinationBlend());
    assertEquals(.65f,m.parameters().get("_Cutoff").get(0),1e-6);assertEquals(.5f,m.parameters().get("_OutlineWidth").get(0));
    assertEquals(.35f,m.parameters().get("_ShadingGradeRate").get(0));assertEquals(.4f,m.parameters().get("_LightColorAttenuation").get(0));
    assertArrayEquals(new float[]{2,3,.1f,.2f},m.parameters().get("_MainTex_ST").copy(),1e-6f);
    assertArrayEquals(new float[]{.6f,.7f,.8f,.9f},m.parameters().get("_Color").copy());
    assertEquals(VrmMaterials.Transfer.SRGB,m.textures().get("_ShadingGradeTexture").transfer());
    assertEquals(VrmMaterials.Transfer.LINEAR,m.textures().get("_BumpMap").transfer());
    assertEquals(0,m.textures().get("_UvAnimMaskTexture").channel());assertEquals(VrmMaterials.UvRule.STATIC,m.textures().get("_OutlineWidthTexture").uvRule());
    assertEquals(VrmMaterials.UvRule.MATCAP,m.textures().get("_SphereAdd").uvRule());
    assertArrayEquals(new float[]{.2f,.3f},evaluator.uv(m,"_SphereAdd",.2f,.3f,.4f).copy(),1e-6f);
    assertEquals(.5f,evaluator.evaluate(null,0).materials().get(0).parameters().get("_Cutoff").get(0));
  }
  @Test void versionedUvOrderUnitsAndMaskAreDeterministicAcrossSeeks() throws Exception {
    var oldJson=legacy();var values=extension(oldJson,true).getAsJsonArray("materialProperties").get(0).getAsJsonObject();
    values.getAsJsonObject("vectorProperties").add("_MainTex",JsonParser.parseString("[0.1,0.2,2,3]"));
    values.getAsJsonObject("floatProperties").addProperty("_UvAnimScrollX",.4);values.getAsJsonObject("floatProperties").addProperty("_UvAnimScrollY",.2);
    values.getAsJsonObject("floatProperties").addProperty("_UvAnimRotation",1);
    var old=new VrmMaterialEvaluator(read(oldJson));var oldFrame=old.evaluate(null,.5).materials().get(0);
    // glTF (.25,.75) -> transformed bottom-left (.6,.95), scroll by (.1,.05), rotate +pi/2, convert back.
    assertArrayEquals(new float[]{0,.3f},old.uv(oldFrame,"_MainTex",.25f,.75f,.5f).copy(),1e-6f);
    assertArrayEquals(new float[]{.6f,.05f},old.uv(oldFrame,"_MainTex",.25f,.75f,0).copy(),1e-6f);
    var json=modernTextures();var toon=toon(json);toon.addProperty("uvAnimationScrollXSpeedFactor",.4);toon.addProperty("uvAnimationScrollYSpeedFactor",.2);
    toon.addProperty("uvAnimationRotationSpeedFactor",Math.PI*2);var evaluator=new VrmMaterialEvaluator(read(json));
    var frame=evaluator.evaluate(null,.5).materials().get(0);
    // New version rotates mesh UV, then scrolls, then applies each KHR texture transform.
    assertArrayEquals(new float[]{.8f,1.1f},evaluator.uv(frame,"baseColor",.25f,.75f,.5f).copy(),1e-6f);
    evaluator.evaluate(null,9);assertEquals(evaluator.uv(frame,"baseColor",.25f,.75f,.5f),evaluator.uv(evaluator.evaluate(null,.5).materials().get(0),"baseColor",.25f,.75f,.5f));
    assertEquals(2,frame.textures().get(MTOON+"uvAnimationMaskTexture").channel());assertEquals(1,frame.textures().get(MTOON+"outlineWidthMultiplyTexture").channel());
    assertEquals(VrmMaterials.Transfer.LINEAR,frame.textures().get(MTOON+"shadingShiftTexture").transfer());
  }
  @Test void reportsSourceAmbiguityCustomShadersAndUnevaluatedExtensionsAndRejectsInvalidRenderModes() throws Exception {
    var json=modernTextures();var d=read(json);var m=new VrmMaterialEvaluator(d).evaluate(null,0).materials().get(0);
    assertArrayEquals(new float[]{1,1,1},m.parameters().get(MTOON+"shadeColorFactor").copy());assertFalse(m.compatibility().diagnostics().isEmpty());
    toon(json).addProperty("renderQueueOffsetNumber",1);assertThrows(IllegalArgumentException.class,()->new VrmMaterialEvaluator(read(json)).evaluate(null,0));
    toon(json).addProperty("renderQueueOffsetNumber",0);toon(json).addProperty("specVersion","2.0");assertThrows(IllegalArgumentException.class,()->new VrmMaterialEvaluator(read(json)).evaluate(null,0));
    var custom=legacy();extension(custom,true).getAsJsonArray("materialProperties").get(0).getAsJsonObject().addProperty("shader","custom/opaque");
    extension(custom,true).getAsJsonArray("materialProperties").get(0).getAsJsonObject().getAsJsonObject("floatProperties").addProperty("_Cutoff",.5);
    assertFalse(new VrmMaterialEvaluator(read(custom)).evaluate(null,0).materials().get(0).compatibility().canEvaluateRequiredFeatures());
    var unknown=modernTextures();unknown.getAsJsonArray("materials").get(0).getAsJsonObject().getAsJsonObject("extensions").add("VENDOR_behavior",new JsonObject());
    assertFalse(new VrmMaterialEvaluator(read(unknown)).evaluate(null,0).materials().get(0).compatibility().canEvaluateRequiredFeatures());
  }
  private static JsonObject legacy() {
    var json=avatar(true);images(json);extension(json,true).add("materialProperties",JsonParser.parseString("""
      [{"name":"face","shader":"VRM/MToon","renderQueue":3077,"floatProperties":{"_CullMode":1,"_ZWrite":1,"_SrcBlend":5,"_DstBlend":10,
        "_ShadingGradeRate":0.35,"_LightColorAttenuation":0.4},"vectorProperties":{"_MainTex":[0.1,0.2,2,3],"_Color":[0.6,0.7,0.8,0.9]},
        "textureProperties":{"_MainTex":0,"_BumpMap":0,"_ShadingGradeTexture":0,"_UvAnimMaskTexture":0,"_OutlineWidthTexture":0,"_SphereAdd":0},"keywordMap":{"_ALPHABLEND_ON":true}}]
      """));
    extension(json,true).add("blendShapeMaster",JsonParser.parseString("{\"blendShapeGroups\":[{\"presetName\":\"joy\",\"materialValues\":[{\"materialName\":\"face\",\"propertyName\":\"_Cutoff\",\"targetValue\":[0.8]}]}]}"));return json;
  }
  private static JsonObject modernTextures() {
    var json=avatar(false);images(json);var material=json.getAsJsonArray("materials").get(0).getAsJsonObject();
    material.getAsJsonObject("pbrMetallicRoughness").add("baseColorTexture",JsonParser.parseString("{\"index\":0,\"extensions\":{\"KHR_texture_transform\":{\"scale\":[2,3],\"offset\":[0.1,0.2]}}}"));
    material.add("extensions",JsonParser.parseString("{\"VRMC_materials_mtoon\":{\"specVersion\":\"1.0\",\"uvAnimationMaskTexture\":{\"index\":0},\"outlineWidthMultiplyTexture\":{\"index\":0},\"shadingShiftTexture\":{\"index\":0}}}"));return json;
  }
  private static JsonObject toon(JsonObject json) { return json.getAsJsonArray("materials").get(0).getAsJsonObject().getAsJsonObject("extensions").getAsJsonObject("VRMC_materials_mtoon"); }
  private static void images(JsonObject json) { json.add("images",JsonParser.parseString("[{\"uri\":\"data:image/png;base64,AA==\"}]"));json.add("textures",JsonParser.parseString("[{\"source\":0}]")); }
}
