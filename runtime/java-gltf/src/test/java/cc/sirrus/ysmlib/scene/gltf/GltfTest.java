package cc.sirrus.ysmlib.scene.gltf;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import cc.sirrus.ysmlib.scene.java.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GltfTest {
  @Test void meshCanMixPrimitivesWithAndWithoutMorphTargets() throws Exception {
    var bytes=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
    bytes.putFloat(1).putFloat(2).putFloat(3).putFloat(0).putFloat(0).putFloat(2);
    String text="""
      {"asset":{"version":"2.0"},"buffers":[{"uri":"data.bin","byteLength":24}],
       "bufferViews":[{"buffer":0,"byteLength":24}],
       "accessors":[{"bufferView":0,"componentType":5126,"count":1,"type":"VEC3"},
         {"bufferView":0,"byteOffset":12,"componentType":5126,"count":1,"type":"VEC3"}],
       "meshes":[{"weights":[0.5],"primitives":[{"mode":0,"attributes":{"POSITION":0}},
         {"mode":0,"attributes":{"POSITION":0},"targets":[{"POSITION":1}]}]}],
       "nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}
      """;
    var scene=new GltfReader().read(json(text),ignored->new ByteData(bytes.array()),ReadLimits.DEFAULT).scene();
    var pose=new cc.sirrus.ysmlib.scene.java.SceneEvaluator(scene).evaluate(null,0);
    var geometry=new cc.sirrus.ysmlib.scene.java.SceneGeometryCompiler(scene,0).compile(pose).draws().get(0).geometry();
    assertEquals(0,scene.meshes().get(0).primitives().get(0).morphs().size());
    assertEquals(3,geometry.primitives().get(0).attributes().get("POSITION").values().get(2));
    assertEquals(4,geometry.primitives().get(1).attributes().get("POSITION").values().get(2));
    assertThrows(AssetFormatException.class,()->new GltfReader().read(json(text.replace("\"targets\":[{\"POSITION\":1}]","\"targets\":[{\"POSITION\":1},{\"POSITION\":1}]")),ignored->new ByteData(bytes.array()),ReadLimits.DEFAULT));
  }
  @TempDir Path temporary;
  private ByteData resource(String path) throws Exception {
    try(var input=getClass().getResourceAsStream("/khronos/"+path)) { assertNotNull(input);return new ByteData(input.readAllBytes()); }
  }
  private GltfDocument sample(String name) throws Exception {
    String base=name+"/glTF/";
    return new GltfReader().read(resource(base+name+".gltf"),reference->{ try { return resource(base+reference); } catch(Exception e) { throw new java.io.IOException(e); } },ReadLimits.DEFAULT);
  }
  private ByteData json(String text) { return new ByteData(text.getBytes(StandardCharsets.UTF_8)); }
  @Test void khronosInterleavedBoxRetainsAllVerticesAndSeparateNormals() throws Exception {
    var d=sample("BoxInterleaved");var mesh=d.scene().meshes().get(0).primitives().get(0);
    assertEquals(24,mesh.vertexCount());assertEquals(36,mesh.indices().size());
    float[] p=mesh.attributes().get("POSITION").values().copy();for(float v:p) assertEquals(.5,Math.abs(v),1e-6);
    var normals=mesh.attributes().get("NORMAL").values();for(int i=0;i<normals.size();i+=3) assertEquals(1,Math.abs(normals.get(i))+Math.abs(normals.get(i+1))+Math.abs(normals.get(i+2)),1e-6);
  }
  @Test void khronosSparseOverridesApplyToTheirIndexedVertices() throws Exception {
    var d=sample("SimpleSparseAccessor");var p=d.scene().meshes().get(0).primitives().get(0).attributes().get("POSITION").values();
    assertEquals(42,p.size());assertEquals(2,p.get(8*3+1),1e-6);assertEquals(3,p.get(10*3+1),1e-6);assertEquals(4,p.get(12*3+1),1e-6);
    assertEquals(0,p.get(0),1e-6);assertEquals(0,p.get(1),1e-6);
  }
  @Test void khronosSkinUsesFullSceneAndInverseBindMatrices() throws Exception {
    var d=sample("SimpleSkin");var scene=d.scene();assertEquals(3,scene.nodes().size());
    var evaluator=new SceneEvaluator(scene);var primitive=scene.meshes().get(0).primitives().get(0);var clip=scene.animations().get(0);
    var rest=evaluator.evaluate(clip,0);var palette=evaluator.skinPalette(rest,0);
    var deformed=new Deformer().deform(primitive,palette,FloatData.EMPTY,Deformer.NormalMode.INVERSE_TRANSPOSE);
    assertArrayEquals(primitive.attributes().get("POSITION").values().copy(),deformed.get("POSITION").values().copy(),1e-5f);
    var moved=evaluator.evaluate(clip,2);var animated=new Deformer().deform(primitive,evaluator.skinPalette(moved,0),FloatData.EMPTY,Deformer.NormalMode.INVERSE_TRANSPOSE);
    assertNotEquals(deformed.get("POSITION"),animated.get("POSITION"));
    var quarterTurn=evaluator.evaluate(clip,1);var turned=new Deformer().deform(primitive,evaluator.skinPalette(quarterTurn,0),FloatData.EMPTY,Deformer.NormalMode.INVERSE_TRANSPOSE);
    assertEquals(-1,turned.get("POSITION").values().get(27),1e-5);assertEquals(1.5f,turned.get("POSITION").values().get(28),1e-5);
    assertEquals(rest,evaluator.evaluate(clip,0));
  }
  @Test void khronosMorphAnimationIncludesNormalAndTangentDeltas() throws Exception {
    var d=sample("AnimatedMorphCube");var scene=d.scene();var mesh=scene.meshes().get(0).primitives().get(0);
    assertEquals(2,mesh.morphs().size());assertEquals(Set.of("NORMAL","POSITION","TANGENT"),mesh.morphs().get(0).attributes().keySet());
    var evaluator=new SceneEvaluator(scene);var clip=scene.animations().get(0);
    var pose=evaluator.evaluate(clip,clip.durationSeconds()/3);var result=new Deformer().deform(mesh,List.of(),pose.morphWeights().get(0),Deformer.NormalMode.INVERSE_TRANSPOSE);
    assertNotEquals(mesh.attributes().get("POSITION"),result.get("POSITION"));assertEquals(4,result.get("TANGENT").components());
    for(int i=0;i<mesh.vertexCount();i++) assertEquals(mesh.attributes().get("TANGENT").values().get(i*4+3),result.get("TANGENT").values().get(i*4+3));
  }
  @Test void glbHeaderAndBinaryChunkAreValidated() throws Exception {
    String text="""
        {"asset":{"version":"2.0"},"buffers":[{"byteLength":12}],"bufferViews":[{"buffer":0,"byteLength":12}],
        "accessors":[{"bufferView":0,"componentType":5126,"count":1,"type":"VEC3"}],
        "meshes":[{"primitives":[{"attributes":{"POSITION":0},"mode":0}]}],"nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}
        """;
    byte[] raw=text.getBytes(StandardCharsets.UTF_8);int length=(raw.length+3)&~3;var bytes=ByteBuffer.allocate(12+8+length+8+12).order(ByteOrder.LITTLE_ENDIAN);
    bytes.putInt(0x46546c67).putInt(2).putInt(bytes.capacity()).putInt(length).putInt(0x4e4f534a).put(raw);
    while(bytes.position()<20+length) bytes.put((byte)32);bytes.putInt(12).putInt(0x004e4942).putFloat(1).putFloat(2).putFloat(3);
    var d=new GltfReader().read(new ByteData(bytes.array()),AssetResolver.NONE,ReadLimits.DEFAULT);
    assertArrayEquals(new float[]{1,2,3},d.scene().meshes().get(0).primitives().get(0).attributes().get("POSITION").values().copy());
    bytes.putInt(8,100);assertThrows(AssetFormatException.class,()->new GltfReader().read(new ByteData(bytes.array()),AssetResolver.NONE,ReadLimits.DEFAULT));
  }
  @Test void normalizedMultiSetWeightsAndUvSetsAreNotTruncated() throws Exception {
    var bin=ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);bin.putFloat(1).putFloat(0).putFloat(0);
    bin.put(new byte[]{0,1,2,3,4,5,6,7});bin.put(new byte[]{32,32,32,32,32,32,32,31});bin.putFloat(.2f).putFloat(.3f).putFloat(.4f);
    String text="""
      {"asset":{"version":"2.0"},"buffers":[{"uri":"data.bin","byteLength":40}],"bufferViews":[{"buffer":0,"byteLength":40}],
       "accessors":[{"bufferView":0,"componentType":5126,"count":1,"type":"VEC3"},
         {"bufferView":0,"byteOffset":12,"componentType":5121,"count":1,"type":"VEC4"},
         {"bufferView":0,"byteOffset":16,"componentType":5121,"count":1,"type":"VEC4"},
         {"bufferView":0,"byteOffset":20,"componentType":5121,"normalized":true,"count":1,"type":"VEC4"},
         {"bufferView":0,"byteOffset":24,"componentType":5121,"normalized":true,"count":1,"type":"VEC4"},
         {"bufferView":0,"byteOffset":28,"componentType":5126,"count":1,"type":"VEC2"}],
       "meshes":[{"primitives":[{"mode":0,"attributes":{"POSITION":0,"JOINTS_0":1,"JOINTS_1":2,"WEIGHTS_0":3,"WEIGHTS_1":4,"TEXCOORD_5":5}}]}]}
      """;
    var scene=new GltfReader().read(json(text),ref->new ByteData(bin.array()),ReadLimits.DEFAULT).scene();var primitive=scene.meshes().get(0).primitives().get(0);
    assertEquals(8,primitive.skinning().joints().size());assertEquals(7,primitive.skinning().joints().get(7));
    assertEquals(31/255f,primitive.skinning().weights().get(7),1e-6);assertTrue(primitive.attributes().containsKey("TEXCOORD_5"));
  }
  @Test void matrixPaddingAndSparseWithoutBaseAreDecoded() throws Exception {
    var bin=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);bin.put(new byte[]{1,2,3,99,4,5,6,99,7,8,9,99,0,0,0,0});
    var root=com.google.gson.JsonParser.parseString("""
      {"buffers":[{"uri":"x","byteLength":16}],"bufferViews":[{"buffer":0,"byteLength":12},{"buffer":0,"byteOffset":12,"byteLength":1}],
       "accessors":[{"componentType":5121,"count":1,"type":"MAT3","sparse":{"count":1,"indices":{"bufferView":1,"componentType":5121},"values":{"bufferView":0}}}]}
      """).getAsJsonObject();
    var buffers=new GltfBuffers(root,null,ref->new ByteData(bin.array()),ReadLimits.DEFAULT,0);
    assertArrayEquals(new float[]{1,2,3,4,5,6,7,8,9},buffers.accessor(0).values().copy());
  }
  @Test void externalLightsAndUnknownRequiredExtensionsAreVisible() throws Exception {
    var d=new GltfReader().read(json("""
      {"asset":{"version":"2.0"},"extensionsUsed":["KHR_lights_punctual","VENDOR_unknown"],"extensionsRequired":["VENDOR_unknown"],
       "extensions":{"KHR_lights_punctual":{"lights":[{"type":"directional","color":[1,0.5,0],"intensity":3}]},"VENDOR_unknown":{"value":12}},
       "nodes":[{"extensions":{"KHR_lights_punctual":{"light":0}}}]}
      """),AssetResolver.NONE,ReadLimits.DEFAULT);
    assertEquals(3,d.scene().lights().get(0).intensity());assertEquals(0,d.scene().nodes().get(0).light());assertFalse(d.scene().compatibility().canEvaluateRequiredFeatures());
    assertTrue(d.scene().metadata().get("extensions").contains("VENDOR_unknown"));
  }
  @Test void malformedJsonCyclesAndUnresolvedDependenciesFail() {
    var reader=new GltfReader();
    for(String text:List.of("{\"asset\":{\"version\":\"2.0\",\"version\":\"1.0\"}}","{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{\"children\":[0]}]}",
        "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"uri\":\"https://example.com/data\",\"byteLength\":12}]}"))
      assertThrows(AssetFormatException.class,()->reader.read(json(text),AssetResolver.NONE,ReadLimits.DEFAULT));
  }
  @Test void directoryResolverChecksDecodedContainmentAndKeepsPlus() throws Exception {
    Files.write(temporary.resolve("a+b.bin"),new byte[]{1,2,3});var resolver=new DirectoryAssetResolver(temporary,100);
    assertEquals(3,resolver.resolve("a+b.bin").size());assertEquals(3,resolver.resolve("a%2bb.bin").size());
    for(String path:List.of("../secret.bin","%2e%2e/secret.bin","https://example.com/file","C:/absolute.bin","//server/share","%2e%2e%5csecret.bin"))
      assertThrows(AssetFormatException.class,()->resolver.resolve(path));
  }
}
