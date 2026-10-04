package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenePackageImagesTest {
  final SceneProvider services=YsmRuntime.scenes();
  final ReadLimits limits=ReadLimits.DEFAULT;
  final ScenePackage.Settings settings=new ScenePackage.Settings(1,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD);
  @Test void gltfImageDomainsAndSharedDecodedPixelsKeepDifferentSamplerBindings() throws Exception {
    var source=gltf(true);var assets=services.loadPackage(source,limits);
    var prepared=services.images(assets,new ReadLimits(4096,6,100));
    var first=prepared.require(ScenePackageImages.Key.indexed("model/avatar.gltf",0));
    var second=prepared.require(ScenePackageImages.Key.indexed("model/avatar.gltf",1));
    assertSame(first,second);assertEquals(2,prepared.images().size());
    var scene=assertInstanceOf(ScenePackageAssets.Gltf.class,assets.model()).value().scene();
    assertEquals(33071,scene.textures().get(0).wrapS());assertEquals(10497,scene.textures().get(1).wrapS());
    assertThrows(UnsupportedOperationException.class,()->prepared.images().clear());
  }
  @Test void uniqueImagesUseCumulativePixelAndMemoryBudgets() throws Exception {
    var assets=services.loadPackage(gltf(false),limits);
    assertThrows(AssetFormatException.class,()->services.images(assets,new ReadLimits(4096,6,100)));
    assertEquals(2,services.images(assets,limits).images().size());
  }
  @Test void pmmChildTexturesUseRelocatedModelOwnershipAndActualDecoding() throws Exception {
    var source=pmm(resource("/scene-image-oracle/rgb.bmp"));
    var assets=services.loadPackage(source,limits);var prepared=services.images(assets,limits);
    var image=prepared.require(ScenePackageImages.Key.named("models/pmd.pmd","toon01.bmp"));
    assertEquals(SceneImage.Format.BMP,image.format());assertEquals(3,image.width());assertEquals(2,image.height());
    assertEquals(231f/255,image.sample(0,0,0),1e-6);
    assertTrue(prepared.images().keySet().stream().noneMatch(key->key.owner().equals("projects/project.pmm")));
  }
  @Test void sourceClosureDoesNotMisreportOpaqueBytesAsDecodedImages() throws Exception {
    var assets=services.loadPackage(pmm(new ByteData(new byte[]{4,5,6})),limits);
    var failure=assertThrows(AssetFormatException.class,()->services.images(assets,limits));
    assertTrue(failure.getMessage().contains("toon01.bmp"));
  }
  @Test void missingPmdDefaultToonUsesPinnedLibraryImageButCustomPathMustExist() throws Exception {
    for(String toon:new String[]{null,"toon01.bmp","TOON10.BMP"}) {
      var assets=services.loadPackage(minimalPmd(toon,Map.of()),limits);
      var image=services.images(assets,limits).require(ScenePackageImages.Key.named("model.pmd",toon==null?"toon01.bmp":toon));
      int index=toon!=null && toon.contains("10")?9:0;
      assertEquals(services.readImage(services.sharedMmdToon(index),"bmp",limits),image);
      assertTrue(assets.dependencies().isEmpty());
    }
    assertThrows(AssetFormatException.class,()->services.loadPackage(minimalPmd("custom/toon01.bmp",Map.of()),limits));
    assertThrows(AssetFormatException.class,()->services.loadPackage(minimalPmd("missing.bmp",Map.of()),limits));
    assertThrows(AssetFormatException.class,()->services.loadPackage(minimalPmd("../toon01.bmp",Map.of()),limits));
    assertTrue(services.images(services.loadPackage(minimalPmd("",Map.of()),limits),limits).images().isEmpty());
  }
  @Test void libraryToonsHaveIndependentDomainAndAllTenDecodeWithinCumulativeBudget() throws Exception {
    for(int i=0;i<10;i++) {
      var bytes=services.sharedMmdToon(i);var decoded=services.readImage(bytes,"bmp",limits);
      assertEquals(SceneImage.Format.BMP,decoded.format());assertEquals(32,decoded.width());assertEquals(32,decoded.height());
      assertNotEquals(ScenePackageImages.Key.indexed("model.pmx",i),ScenePackageImages.Key.sharedToon(i));
    }
    assertThrows(IllegalArgumentException.class,()->services.sharedMmdToon(10));
    var assets=services.loadPackage(minimalPmd(null,Map.of()),limits);
    assertThrows(AssetFormatException.class,()->services.images(assets,new ReadLimits(4096,100,100)));
    var override=resource("/scene-image-oracle/rgb.bmp");
    var overridden=services.loadPackage(minimalPmd(null,Map.of("toon01.bmp",override)),limits);
    assertEquals("toon01.bmp",overridden.dependencies().get(new ScenePackageAssets.Reference("model.pmd","toon01.bmp")));
    assertEquals(3,services.images(overridden,limits).require(ScenePackageImages.Key.named("model.pmd","toon01.bmp")).width());
    var broken=services.loadPackage(minimalPmd(null,Map.of("toon01.bmp",new ByteData(new byte[]{1,2,3}))),limits);
    assertThrows(AssetFormatException.class,()->services.images(broken,limits));
  }
  ScenePackage minimalPmd(String toon,Map<String,ByteData> dependencies) {
    var b=java.nio.ByteBuffer.allocate(2048).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    b.put(new byte[]{'P','m','d'}).putFloat(1).put(new byte[276]).putInt(0).putInt(0).putInt(1);
    for(float value:new float[]{1,1,1,1,1,0,0,0,0,0,0}) b.putFloat(value);
    b.put((byte)0).put((byte)0).putInt(0).put(new byte[20]);
    b.putShort((short)0).putShort((short)0).putShort((short)0).put((byte)0).put((byte)0).putInt(0);
    if(toon!=null) {
      b.put((byte)0);byte[] text=toon.getBytes(StandardCharsets.US_ASCII);
      b.put(text).put(new byte[100-text.length]).put(new byte[900]);
    }
    var files=new HashMap<>(dependencies);files.put("model.pmd",new ByteData(Arrays.copyOf(b.array(),b.position())));
    return new ScenePackage(new ScenePackage.Source("avatar","model.pmd",ScenePackage.Format.PMD),settings,List.of(),List.of(),files);
  }
  ScenePackage gltf(boolean duplicate) throws Exception {
    var text="""
      {"asset":{"version":"2.0"},"images":[{"uri":"a.bmp"},{"uri":"b.bmp"}],
       "samplers":[{"wrapS":33071},{"wrapS":10497}],
       "textures":[{"source":0,"sampler":0},{"source":1,"sampler":1}]}
      """;
    return new ScenePackage(new ScenePackage.Source("avatar","model/avatar.gltf",ScenePackage.Format.GLTF),settings,List.of(),List.of(),
        Map.of("model/avatar.gltf",new ByteData(text.getBytes(StandardCharsets.UTF_8)),
            "model/a.bmp",resource("/scene-image-oracle/rgb.bmp"),"model/b.bmp",resource("/scene-image-oracle/"+(duplicate?"rgb.bmp":"rle8.bmp"))));
  }
  ScenePackage pmm(ByteData image) throws Exception {
    return new ScenePackage(new ScenePackage.Source("project","projects/project.pmm",ScenePackage.Format.PMM),settings,List.of(),
        List.of(new ScenePackage.Relocation("projects/project.pmm","C:\\Dance\\models\\pmd.pmd","models/pmd.pmd")),
        Map.of("projects/project.pmm",resource("/pmm-oracle/project-v1.pmm"),"models/pmd.pmd",resource("/mmd-oracle/pmd.pmd"),
            "projects/音楽/dance.wav",new ByteData(new byte[]{1,2,3}),"models/toon01.bmp",image));
  }
  ByteData resource(String name) throws Exception {
    try(var stream=getClass().getResourceAsStream(name)) { return new ByteData(Objects.requireNonNull(stream,name).readAllBytes()); }
  }
}
