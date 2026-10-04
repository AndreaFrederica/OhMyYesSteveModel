package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenePackageLoaderTest {
  final SceneProvider scenes=YsmRuntime.scenes();
  final ReadLimits limits=ReadLimits.DEFAULT;
  final ScenePackage.Settings all=new ScenePackage.Settings(.08,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD);
  ByteData resource(String name) throws Exception {
    try(var stream=getClass().getResourceAsStream(name)) { return new ByteData(Objects.requireNonNull(stream,name).readAllBytes()); }
  }
  @Test void khronosSkinResolvesEveryBufferAndRetainsAnimationThroughThePackage() throws Exception {
    var files=new LinkedHashMap<String,ByteData>();
    for(String name:List.of("SimpleSkin.gltf","SimpleSkin_skinningData.bin","SimpleSkin_inverseBindMatrices.bin","SimpleSkin_geometry.bin","SimpleSkin_animation.bin"))
      files.put("models/"+name,resource("/khronos/SimpleSkin/glTF/"+name));
    var source=new ScenePackage(new ScenePackage.Source("avatar","models/SimpleSkin.gltf",ScenePackage.Format.GLTF),
        new ScenePackage.Settings(1,0,all.fbxSkinSpace()),List.of(),List.of(),files);
    var loaded=scenes.loadPackage(scenes.readPackage(scenes.writePackage(source,limits),limits),limits);
    var gltf=assertInstanceOf(ScenePackageAssets.Gltf.class,loaded.model()).value();
    assertEquals(4,loaded.dependencies().size());assertEquals(1,gltf.scene().animations().size());
    var pose=scenes.evaluator(gltf.scene()).evaluate(gltf.scene().animations().get(0),1);
    assertFalse(scenes.geometry(gltf.scene(),0).compile(pose).draws().isEmpty());
    files.remove("models/SimpleSkin_skinningData.bin");
    var broken=new ScenePackage(source.model(),source.settings(),source.animations(),source.relocations(),files);
    assertThrows(java.io.IOException.class,()->scenes.loadPackage(broken,limits));
  }
  @Test void mmdSourceAndSeparateMotionAreTypedAndKeepTheirOriginalBytes() throws Exception {
    var source=new ScenePackage(new ScenePackage.Source("avatar","model/skin.pmx",ScenePackage.Format.PMX),all,
        List.of(new ScenePackage.Source("dance","motion/skin.vmd",ScenePackage.Format.VMD)),List.of(),
        Map.of("model/skin.pmx",resource("/mmd-oracle/skin.pmx"),"motion/skin.vmd",resource("/mmd-oracle/skin.vmd")));
    var loaded=scenes.loadPackage(source,limits);
    var model=assertInstanceOf(ScenePackageAssets.Pmx.class,loaded.model());
    var motion=assertInstanceOf(ScenePackageAssets.Vmd.class,loaded.documents().get("dance"));
    assertEquals(source.files().get("model/skin.pmx"),model.value().source());
    assertFalse(model.mesh().primitives().isEmpty());assertFalse(motion.animation().clip().tracks().isEmpty());
  }
  @Test void pmmOneReadsItsRelocatedModelSchemaWithoutHostFilesystemAccess() throws Exception {
    var source=new ScenePackage(new ScenePackage.Source("project","projects/project.pmm",ScenePackage.Format.PMM),all,List.of(),
        List.of(new ScenePackage.Relocation("projects/project.pmm","C:\\Dance\\models\\pmd.pmd","models/pmd.pmd")),
        Map.of("projects/project.pmm",resource("/pmm-oracle/project-v1.pmm"),"models/pmd.pmd",resource("/mmd-oracle/pmd.pmd"),
            "projects/音楽/dance.wav",new ByteData(new byte[]{1,2,3}),"models/toon01.bmp",new ByteData(new byte[]{4,5,6})));
    var loaded=scenes.loadPackage(source,limits);var pmm=assertInstanceOf(ScenePackageAssets.Pmm.class,loaded.model());
    assertEquals(1,pmm.value().version());assertEquals(1,pmm.models().size());
    assertInstanceOf(ScenePackageAssets.Pmd.class,pmm.models().values().iterator().next());
    assertEquals("models/pmd.pmd",loaded.dependencies().get(new ScenePackageAssets.Reference("projects/project.pmm","C:\\Dance\\models\\pmd.pmd")));
    var unmapped=new ScenePackage(source.model(),all,List.of(),List.of(),source.files());
    assertThrows(java.io.IOException.class,()->scenes.loadPackage(unmapped,limits));
  }
  @Test void directInMemoryPackagesRemainSubjectToTheAggregateByteLimit() throws Exception {
    var source=new ScenePackage(new ScenePackage.Source("avatar","skin.pmx",ScenePackage.Format.PMX),all,List.of(),List.of(),
        Map.of("skin.pmx",resource("/mmd-oracle/skin.pmx"),"extra",new ByteData(new byte[1024])));
    assertThrows(java.io.IOException.class,()->scenes.loadPackage(source,new ReadLimits(1024,1000,100)));
  }
}
