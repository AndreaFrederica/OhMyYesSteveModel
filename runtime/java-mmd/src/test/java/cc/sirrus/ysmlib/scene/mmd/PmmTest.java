package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PmmTest {
  private byte[] fixture() throws Exception { try(var input=getClass().getResourceAsStream("/pmm-oracle/project.pmm")) { return Objects.requireNonNull(input).readAllBytes(); } }
  private PmmDocument read(byte[] bytes) throws Exception { return new PmmReader().read(new ByteData(bytes),PmmModelResolver.NONE,ReadLimits.DEFAULT); }
  @Test void readsUnmodifiedNanoemWriterProjectWithEverySceneTrackAndForeignPaths() throws Exception {
    var d=read(fixture());assertEquals(2,d.version());assertEquals(2,d.models().size());assertEquals(1,d.accessories().size());
    var m=d.models().get(0);assertEquals("first",m.name());assertEquals("first-en",m.englishName());assertEquals("C:\\Dance\\models\\first.pmd",m.path());
    assertEquals(List.of("root","child"),m.boneNames());assertEquals(List.of("smile"),m.morphNames());assertEquals(4,m.boneKeys().size());
    assertEquals(60,m.boneKeys().get(3).key().frame());assertEquals(1,m.boneKeys().get(3).physicsDisabled());assertEquals(new Vec3(1,2,3),m.boneKeys().get(2).translation());
    assertEquals(.75,m.morphKeys().get(1).weight());assertEquals(90,m.modelKeys().get(1).key().frame());assertEquals(0,m.modelKeys().get(1).visible());
    var a=d.accessories().get(0).keys().get(1);assertEquals(2,a.state().scale());assertEquals(.5,a.state().opacity());assertTrue(a.state().visible());
    assertEquals(-40,d.camera().keys().get(1).distance());assertEquals(35,d.camera().keys().get(1).fov());
    assertEquals(new Vec3(1,2,3),d.light().keys().get(1).color());assertEquals(12,d.gravity().keys().get(1).acceleration());
    assertEquals(2,d.shadow().keys().get(1).mode());assertEquals(.012f,d.shadow().keys().get(1).distance());
    assertEquals("C:\\Dance\\audio.wav",d.audio().path());assertEquals(1,d.audio().enabled());assertEquals(60,d.settings().get("preferredFps").floatValue());
    assertEquals(6,d.assetReferences().size());assertEquals(0,d.trailingData().size());assertEquals(new ByteData(fixture()),d.source());
  }
  @Test void rejectsTruncationAndBudgetsWhileKeepingExplicitExtensionBytes() throws Exception {
    byte[] source=fixture();
    for(int n:new int[]{0,29,80,source.length/2,source.length-8}) assertThrows(AssetFormatException.class,()->read(Arrays.copyOf(source,n)));
    assertThrows(AssetFormatException.class,()->new PmmReader().read(new ByteData(source),PmmModelResolver.NONE,new ReadLimits(source.length,5,256)));
    // Writer ends with an optional model-selection section; append only after its existing flag.
    byte[] extended=Arrays.copyOf(source,source.length+3);extended[source.length]=12;extended[source.length+1]=34;extended[source.length+2]=56;
    assertEquals(new ByteData(new byte[]{12,34,56}),read(extended).trailingData());
  }
  @Test void keyChainsBindToInitialTrackAndRejectCyclesAndDetachedRecords() throws Exception {
    var d=read(fixture());var model=d.models().get(0);
    assertArrayEquals(new int[]{0,1,0,1},PmmValidation.owners(model.boneKeys(),2,PmmDocument.BoneKey::key));
    var key=new PmmDocument.Key(0,0,0,1);var next=new PmmDocument.Key(1,30,0,1);
    assertThrows(AssetFormatException.class,()->PmmValidation.owners(List.of(key,next),1,k->k));
    assertThrows(AssetFormatException.class,()->PmmValidation.owners(List.of(new PmmDocument.Key(0,0,0,0),next),1,k->k));
  }
  @Test void compilesEmbeddedAnimationAndRetainsProjectOnlyTracks() throws Exception {
    var d=read(fixture());var animations=new PmmAnimation().compile(d);assertEquals(2,animations.models().size());
    var sampler=new cc.sirrus.ysmlib.scene.java.ClipSampler();var model=sampler.sample(animations.models().get(0).clip(),1.5);
    var morph=model.channels().stream().filter(c->c.property()==AnimationClip.Property.MORPH_WEIGHTS).findFirst().orElseThrow();assertEquals(.75f,morph.value().get(0));
    var frame=sampler.sample(animations.models().get(0).clip(),2);
    assertEquals(0,frame.channels().stream().filter(c->c.property()==AnimationClip.Property.BONE_PHYSICS_ENABLED && c.binding().equals("child")).findFirst().orElseThrow().value().get(0));
    var scene=sampler.sample(animations.scene().clip(),1);
    assertEquals(12,scene.channels().stream().filter(c->c.property()==AnimationClip.Property.GRAVITY_ACCELERATION).findFirst().orElseThrow().value().get(0));
    assertEquals(30,animations.scene().clip().sourceFramesPerSecond());
    assertFalse(animations.scene().compatibility().canEvaluateRequiredFeatures());
  }
  @Test void pmmOneReadsCp932AndUsesExactExternalModelSchema() throws Exception {
    byte[] project;PmdDocument model;
    try(var input=getClass().getResourceAsStream("/pmm-oracle/project-v1.pmm");var pmd=getClass().getResourceAsStream("/mmd-oracle/pmd.pmd")) {
      project=Objects.requireNonNull(input).readAllBytes();model=new PmdReader().read(new ByteData(Objects.requireNonNull(pmd).readAllBytes()),ReadLimits.DEFAULT);
    }
    var paths=new ArrayList<String>();var d=new PmmReader().read(new ByteData(project),path->{ paths.add(path);return PmmModelResolver.Schema.from(model); },ReadLimits.DEFAULT);
    assertEquals(List.of("C:\\Dance\\models\\pmd.pmd"),paths);assertEquals(1,d.version());assertEquals("旧工程",d.models().get(0).name());
    assertEquals(List.of("root","tip"),d.models().get(0).boneNames());assertEquals(List.of("base","shape"),d.models().get(0).morphNames());
    assertEquals(123,d.models().get(0).boneStates().get(0).unknown());assertEquals("音楽/dance.wav",d.audio().path());assertEquals(new ByteData(new byte[]{7,8,9}),d.trailingData());
    assertEquals(3,d.models().get(0).boneKeys().size());assertNull(d.gravity());assertNull(d.shadow());
    var pose=new cc.sirrus.ysmlib.scene.java.ClipSampler().sample(new PmmAnimation().compile(d).models().get(0).clip(),1);
    assertEquals(1,pose.channels().stream().filter(c->c.property()==AnimationClip.Property.TRANSLATION && c.binding().equals("root")).findFirst().orElseThrow().value().get(0));
    assertThrows(java.io.IOException.class,()->read(project));
  }
}
