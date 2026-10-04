package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class VrmExpressionsTest {
  private JsonObject expressionAvatar() {
    var json=avatar(false);int mesh=json.getAsJsonArray("nodes").size()-1;
    json.add("images",JsonParser.parseString("[{\"uri\":\"data:image/png;base64,AA==\"}]"));json.add("textures",JsonParser.parseString("[{\"source\":0}]"));
    json.getAsJsonArray("materials").get(0).getAsJsonObject().getAsJsonObject("pbrMetallicRoughness").add("baseColorTexture",
        JsonParser.parseString("{\"index\":0,\"extensions\":{\"KHR_texture_transform\":{\"scale\":[2,3],\"offset\":[0.1,0.2]}}}"));
    extension(json,false).add("expressions",JsonParser.parseString("""
      {"preset":{
        "happy":{"overrideBlink":"blend","morphTargetBinds":[{"node":NODE,"index":0,"weight":0.8}],
          "materialColorBinds":[{"material":0,"type":"color","targetValue":[0.9,0.1,0.7,0.2]}],
          "textureTransformBinds":[{"material":0,"scale":[0.5,1],"offset":[0.4,0.6]}]},
        "sad":{"isBinary":true,"overrideMouth":"block","morphTargetBinds":[{"node":NODE,"index":0,"weight":0.3}]},
        "blink":{"isBinary":true,"morphTargetBinds":[{"node":NODE,"index":1,"weight":1}]},
        "aa":{"morphTargetBinds":[{"node":NODE,"index":2,"weight":1}]}
      }}
      """.replace("NODE",Integer.toString(mesh))));return json;
  }
  @Test void matchesUnmodifiedThreeVrmAcross35ExpressionCombinations() throws Exception {
    var d=read(expressionAvatar());var evaluator=new VrmExpressionEvaluator(d);int mesh=d.scene().nodes().size()-1;
    for(var value:resource("expressions.json").getAsJsonArray()) {
      var frame=value.getAsJsonObject();var weights=new LinkedHashMap<String,Float>();frame.getAsJsonObject("weights").entrySet().forEach(e->weights.put(e.getKey(),e.getValue().getAsFloat()));
      var result=evaluator.evaluate(weights);assertArray(frame.getAsJsonArray("morphs"),result.morphWeights().get(mesh).copy(),2e-6);
      assertArray(frame.getAsJsonArray("color"),result.materials().get(0).parameters().get("baseColor").copy(),2e-6);
      var uv=result.materials().get(0).textureTransforms().get("baseColor");assertArray(frame.getAsJsonArray("scale"),new float[]{uv.get(0),uv.get(4)},2e-6);
      assertArray(frame.getAsJsonArray("offset"),new float[]{uv.get(6),uv.get(7)},2e-6);
    }
    assertArrayEquals(new float[]{.2f,.3f,.4f,.8f},evaluator.evaluate(Map.of()).materials().get(0).parameters().get("baseColor").copy());
    assertThrows(IllegalArgumentException.class,()->evaluator.evaluate(Map.of("happy",Float.NaN)));
    assertThrows(IllegalArgumentException.class,()->evaluator.evaluate(Map.of("missing",1f)));
  }
  @Test void sameCategoryOverrideIsIgnoredAndBinaryIsNotPartiallyExpressed() throws Exception {
    var json=expressionAvatar();var preset=extension(json,false).getAsJsonObject("expressions").getAsJsonObject("preset");
    preset.getAsJsonObject("blink").addProperty("overrideBlink","block");
    var evaluator=new VrmExpressionEvaluator(read(json));
    var same=evaluator.evaluate(Map.of("blink",1f,"sad",.5f,"aa",1f));assertEquals(1,same.effectiveWeights().get("blink"));assertEquals(1,same.effectiveWeights().get("aa"));
    var overridden=evaluator.evaluate(Map.of("blink",1f,"happy",.01f,"sad",.5001f,"aa",1f));assertEquals(0,overridden.effectiveWeights().get("blink"));assertEquals(0,overridden.effectiveWeights().get("aa"));
    var clamp=evaluator.evaluate(Map.of("happy",2f,"sad",-1f));assertEquals(1,clamp.effectiveWeights().get("happy"));assertEquals(0,clamp.effectiveWeights().get("sad"));
  }
  @Test void customProceduralCategoriesAndMtoonMatcapExclusionAreExplicit() throws Exception {
    var json=expressionAvatar();var expressions=extension(json,false).getAsJsonObject("expressions");expressions.add("custom",JsonParser.parseString("{\"customBlink\":{}}"));
    var mat=json.getAsJsonArray("materials").get(0).getAsJsonObject();mat.add("extensions",JsonParser.parseString("{\"VRMC_materials_mtoon\":{\"specVersion\":\"1.0\",\"matcapTexture\":{\"index\":0},\"shadeMultiplyTexture\":{\"index\":0}}}"));
    var evaluator=new VrmExpressionEvaluator(read(json));var out=evaluator.evaluate(Map.of("happy",1f,"customBlink",1f),Map.of("customBlink",VrmDocument.Category.BLINK));
    assertEquals(0,out.effectiveWeights().get("customBlink"));
    assertEquals(1,out.materials().get(0).textureTransforms().get("VRMC_materials_mtoon/matcapTexture").get(0));
    assertEquals(.5,out.materials().get(0).textureTransforms().get("VRMC_materials_mtoon/shadeMultiplyTexture").get(0));
  }
  private static void assertArray(JsonArray expected,float[] actual,double tolerance) {
    assertEquals(expected.size(),actual.length);for(int i=0;i<actual.length;i++) assertEquals(expected.get(i).getAsDouble(),actual[i],tolerance,"component "+i);
  }
  @Test void legacyUvAxisBindingsComposeAndShaderColorsKeepTheirSourceSpace() throws Exception {
    var json=avatar(true);var ext=extension(json,true);
    ext.add("materialProperties",JsonParser.parseString("[{\"name\":\"face\",\"shader\":\"VRM/MToon\",\"vectorProperties\":{\"_Color\":[0.5,0.6,0.7,1],\"_MainTex\":[0,0,1,1]}}]"));
    ext.add("blendShapeMaster",JsonParser.parseString("""
      {"blendShapeGroups":[{"name":"x","materialValues":[{"materialName":"face","propertyName":"_MainTex_ST_S","targetValue":[2,9,0.2,9]}]},
       {"name":"y","materialValues":[{"materialName":"face","propertyName":"_MainTex_ST_T","targetValue":[9,3,9,0.3]}]},
       {"name":"color","materialValues":[{"materialName":"face","propertyName":"_Color","targetValue":[1,1,1,1]}]}]}
      """));
    var frame=new VrmExpressionEvaluator(read(json)).evaluate(Map.of("x",.5f,"y",.5f,"color",.5f));var p=frame.materials().get(0).parameters();
    assertArrayEquals(new float[]{1.5f,2,.1f,.15f},p.get("_MainTex_ST").copy(),1e-6f);assertArrayEquals(new float[]{.75f,.8f,.85f,1},p.get("_Color").copy(),1e-6f);
    assertArrayEquals(new float[]{.2f,.3f,.4f,.8f},p.get("baseColor").copy());
  }
}
