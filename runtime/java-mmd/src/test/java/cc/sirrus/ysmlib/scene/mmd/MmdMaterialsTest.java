package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MmdMaterialsTest {
  @Test void pmdKeepsLiteralPathsAndOnlyBareDefaultToonsCanFallBack() throws Exception {
    var pmd=pmd();var material=pmd.materials().get(0);
    var named=new PmdDocument.Material(material.diffuse(),material.alpha(),material.shininess(),material.specular(),material.ambient(),0,material.edgeFlag(),material.indexCount(),"face.png*reflection.SPA");
    var profile=new MmdMaterialEvaluator(change(pmd,List.of(named),List.of()));
    var definition=profile.definitions().get(0);
    assertEquals("face.png",definition.diffuse().reference());assertEquals("reflection.SPA",definition.sphere().reference());
    assertEquals(MmdMaterials.SphereMode.ADD,definition.sphereMode());
    assertEquals("toon01.bmp",definition.toon().reference());assertEquals(0,definition.toon().fallbackToon());
    assertEquals(ScenePackageImages.Key.named("model.pmd","toon01.bmp"),definition.toon().key("model.pmd"));
    var table=new ArrayList<>(Collections.nCopies(10,""));table.set(0,"custom/toon01.bmp");
    assertEquals(-1,new MmdMaterialEvaluator(change(pmd,List.of(named),table)).definitions().get(0).toon().fallbackToon());
    table.set(0,"TOON10.BMP");assertEquals(9,new MmdMaterialEvaluator(change(pmd,List.of(named),table)).definitions().get(0).toon().fallbackToon());
    table.set(0,"");assertNull(new MmdMaterialEvaluator(change(pmd,List.of(named),table)).definitions().get(0).toon());
    assertFalse(new PmdRuntimeProfile(change(pmd,List.of(named),List.of())).asset.materials().get(0).sharedToon());
  }
  @Test void pmxSharedToonIsIndependentOfUserImageIndicesAndFinalMorphValuesAreNotAppliedTwice() {
    var names=new PmxDocument.Names("surface","");
    var material=new PmxDocument.Material(names,new FloatData(.1f,.2f,.3f,.4f),new Vec3(.5f,.6f,.7f),16,new Vec3(.8f,.9f,1),0xff,
        new FloatData(.3f,.4f,.5f,.6f),2,0,1,3,true,8,"",0);
    var source=new PmxDocument(2.1f,new ByteData(new byte[]{1,1,4,4,4,4,4,4}),names,"","",List.of(),IntData.EMPTY,
        List.of("toon09.bmp","sub.png"),List.of(material),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),ByteData.EMPTY);
    var profile=new MmdMaterialEvaluator(source);var definition=profile.definitions().get(0);
    assertEquals(ScenePackageImages.Key.sharedToon(8),definition.toon().key("avatar.pmx"));
    assertNotEquals(definition.toon().key("avatar.pmx"),definition.diffuse().key("avatar.pmx"));
    assertTrue(definition.doubleSided());assertTrue(definition.vertexColor());assertTrue(definition.points());assertTrue(definition.lines());
    assertEquals(MmdMaterials.SphereMode.SUB_TEXTURE,definition.sphereMode());
    var morph=new MmdMorphState.Material(new FloatData(2,3,4,.25f),Vec3.ZERO,0,Vec3.ZERO,new FloatData(1,0,0,1),4,
        new FloatData(.2f,.3f,.4f,.5f),new FloatData(.6f,.7f,.8f,.9f),new FloatData(1,1,1,1),new FloatData(0,0,0,0),new FloatData(1,1,1,1),new FloatData(0,0,0,0));
    assertSame(morph,profile.evaluate(List.of(morph)).materials().get(0).values());
    assertEquals(material.diffuse(),profile.rest().materials().get(0).values().diffuse());
    assertThrows(IllegalArgumentException.class,()->profile.evaluate(List.of()));
    assertThrows(UnsupportedOperationException.class,()->profile.definitions().clear());
  }
  @Test void wireDrawKeepsAllThreeEdgesAndPointDrawTakesPrecedenceWithExplicitBudgets() {
    var geometry=new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(0,0,0,1,0,0,0,1,0,1,1,0))),new IntData(0,1,2,2,1,3),0,null,List.of());
    var line=flagged(128);var projected=line.geometry(0,geometry,ReadLimits.DEFAULT);
    assertEquals(MeshAsset.Topology.LINES,projected.topology());assertArrayEquals(new int[]{0,1,1,2,2,0,2,1,1,3,3,2},projected.indices().copy());
    var points=flagged(192).geometry(0,geometry,ReadLimits.DEFAULT);assertEquals(MeshAsset.Topology.POINTS,points.topology());assertSame(geometry.indices(),points.indices());
    assertArrayEquals(new int[]{0,1,2,2,1,3},geometry.indices().copy());
    assertThrows(IllegalArgumentException.class,()->line.geometry(0,geometry,new ReadLimits(40,100,10)));
    assertThrows(IllegalArgumentException.class,()->flagged(32).geometry(0,geometry,ReadLimits.DEFAULT));
    assertSame(geometry,flagged(0).geometry(0,geometry,ReadLimits.DEFAULT));
  }
  private MmdMaterials flagged(int flags) {
    var names=new PmxDocument.Names("wire","");
    var material=new PmxDocument.Material(names,new FloatData(1,1,1,1),Vec3.ZERO,0,Vec3.ZERO,flags,new FloatData(0,0,0,1),1,-1,-1,0,false,-1,"",6);
    return new MmdMaterialEvaluator(new PmxDocument(2.1f,new ByteData(new byte[]{1,0,4,4,4,4,4,4}),names,"","",List.of(),IntData.EMPTY,List.of(),List.of(material),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),ByteData.EMPTY));
  }
  private PmdDocument pmd() throws Exception {
    try(var in=getClass().getResourceAsStream("/mmd-oracle/pmd.pmd")) { return new PmdReader().read(new ByteData(in.readAllBytes()),ReadLimits.DEFAULT); }
  }
  private PmdDocument change(PmdDocument d,List<PmdDocument.Material> materials,List<String> toons) {
    return new PmdDocument(d.name(),d.comment(),d.vertices(),d.indices(),materials,d.bones(),d.ik(),d.morphs(),d.morphDisplay(),d.frameNames(),d.boneDisplay(),d.english(),toons,d.rigidBodies(),d.joints(),d.optionalSections(),d.source());
  }
}
