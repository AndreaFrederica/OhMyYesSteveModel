package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PmxTest {
  /** Wire fixture assembled independently from the reader; explicit distinct values catch field shifts. */
  private static final class Fixture {
    final ByteBuffer b=ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);final int width;final Charset encoding;
    int faceOffset,boneParentOffset;
    Fixture(int width,boolean utf8) { this.width=width;encoding=utf8?StandardCharsets.UTF_8:StandardCharsets.UTF_16LE; }
    void text(String text) { byte[] bytes=text.getBytes(encoding);b.putInt(bytes.length).put(bytes); }
    void names(String name) { text(name);text("en-"+name); }
    void index(int value) { if(width==1) b.put((byte)value);else if(width==2) b.putShort((short)value);else b.putInt(value); }
    void floats(float... values) { for(float v:values) b.putFloat(v); }
    void zeros(int count) { for(int i=0;i<count;i++) b.putFloat(0); }
    void offsets(int count) { for(int i=0;i<count;i++) b.putFloat(i+.125f); }
    ByteData make() {
      b.put(new byte[]{'P','M','X',' '}).putFloat(2.1f).put((byte)8).put((byte)(encoding==StandardCharsets.UTF_8?1:0)).put((byte)4);
      for(int i=0;i<6;i++) b.put((byte)width);names("モデル");text("license");text("license-en");
      b.putInt(5);
      for(int type=0;type<5;type++) {
        floats(type,1,2,0,1,0,.25f,.75f);offsets(16);b.put((byte)type);
        int count=type==0?1:type==1 || type==3?2:4;for(int i=0;i<count;i++) index(0);
        if(count==2) floats(.25f);else if(count==4) floats(.1f,.2f,.3f,.4f);
        if(type==3) offsets(9);floats(1.5f);
      }
      b.putInt(3);faceOffset=b.position();index(0);index(1);index(2);
      b.putInt(1);text("textures/顔.png");b.putInt(1);names("材質");floats(1,.8f,.6f,.4f,1,2,3,4,5,6,7);b.put((byte)31);
      floats(1,0,0,1,2);index(0);index(-1);b.put((byte)3).put((byte)1).put((byte)9);text("memo");b.putInt(3);
      b.putInt(1);names("センター");floats(0,1,0);boneParentOffset=b.position();index(-1);b.putInt(2).putShort((short)0x3f20);
      floats(0,1,0);index(-1);floats(.5f);floats(1,0,0);floats(1,0,0,0,0,1);b.putInt(0x12345678);
      index(0);b.putInt(8);floats(.5f);b.putInt(1);index(0);b.put((byte)1);floats(-1,-2,-3,1,2,3);
      b.putInt(11);
      for(int type=0;type<11;type++) {
        names("morph-"+type);b.put((byte)4).put((byte)type).putInt(1);
        switch(type) {
          case 0,9 -> { index(1);floats(.3f); }
          case 1 -> { index(2);floats(1,2,3); }
          case 2 -> { index(0);floats(1,2,3,0,0,0,1); }
          case 3,4,5,6,7 -> { index(2);offsets(4); }
          case 8 -> { index(-1);b.put((byte)1);offsets(28); }
          case 10 -> { index(0);b.put((byte)1);offsets(6); }
        }
      }
      b.putInt(1);names("display");b.put((byte)1).putInt(2).put((byte)0);index(0);b.put((byte)1);index(2);
      b.putInt(1);names("body");index(0);b.put((byte)5).putShort((short)0x1234).put((byte)2);floats(.5f,2,0,0,1,0,0,0,0,1,.2f,.3f,.4f,.5f);b.put((byte)2);
      b.putInt(6);for(int type=0;type<6;type++) { names("joint-"+type);b.put((byte)type);index(0);index(-1);offsets(24); }
      b.putInt(1);names("soft");b.put((byte)0);index(0);b.put((byte)6).putShort((short)0x3456).put((byte)7).putInt(2).putInt(3);
      floats(5,.01f);b.putInt(4);offsets(12);offsets(6);b.putInt(1).putInt(2).putInt(3).putInt(4);floats(.25f,.5f,.75f);
      b.putInt(1);index(0);index(2);b.put((byte)1);b.putInt(1);index(1);
      return new ByteData(Arrays.copyOf(b.array(),b.position()));
    }
  }
  @Test void everyPmx21SectionAndAllIndexWidthsArePreserved() throws Exception {
    for(int width:new int[]{1,2,4}) for(boolean utf8:new boolean[]{false,true}) {
      var source=new Fixture(width,utf8).make();var d=new PmxReader().read(source,ReadLimits.DEFAULT);
      assertEquals(source,d.source());assertEquals("モデル",d.names().local());assertEquals(5,d.vertices().size());
      for(int i=0;i<5;i++) assertEquals(i,d.vertices().get(i).deform());
      assertEquals(16,d.vertices().get(4).additionalUv().size());assertEquals(15.125f,d.vertices().get(4).additionalUv().get(15));
      assertEquals(new Vec3(.125f,1.125f,2.125f),d.vertices().get(3).sdefC());
      assertEquals(0x12345678,d.bones().get(0).externalParentKey());assertEquals(8,d.bones().get(0).ik().iterations());
      assertEquals(11,d.morphs().size());assertEquals(27.125f,((PmxDocument.MaterialOffset)d.morphs().get(8).offsets().get(0)).toonTint().get(3));
      assertEquals(6,d.joints().size());assertEquals(0x1234,d.rigidBodies().get(0).collisionMask());
      var soft=d.softBodies().get(0);assertEquals(.75f,soft.stiffness().get(2));assertEquals(4,soft.iterations().get(3));
      assertTrue(soft.anchors().get(0).near());assertEquals(1,soft.pins().get(0));
    }
  }
  @Test void truncatedBodiesInvalidReferencesAndCyclesFail() {
    var fixture=new Fixture(1,true);var source=fixture.make();byte[] bytes=source.copy();
    assertThrows(AssetFormatException.class,()->new PmxReader().read(new ByteData(Arrays.copyOf(bytes,bytes.length-1)),ReadLimits.DEFAULT));
    bytes[fixture.faceOffset]=99;assertThrows(AssetFormatException.class,()->new PmxReader().read(new ByteData(bytes),ReadLimits.DEFAULT));
    bytes[fixture.faceOffset]=0;bytes[fixture.boneParentOffset]=0;
    assertThrows(AssetFormatException.class,()->new PmxReader().read(new ByteData(bytes),ReadLimits.DEFAULT));
  }
  @Test void sparseMeshMorphsPreserveSdefQdefAndAllAdditionalUvComponents() throws Exception {
    var document=new PmxReader().read(new Fixture(2,true).make(),ReadLimits.DEFAULT);
    var mesh=new MmdMeshCompiler().compile(document).primitives().get(0);
    assertEquals(MeshAsset.Deform.SDEF,mesh.skinning().deforms().get(3));assertEquals(MeshAsset.Deform.QDEF,mesh.skinning().deforms().get(4));
    assertEquals(.125f,mesh.skinning().sdef().get(27));assertEquals(11,mesh.morphs().size());assertTrue(mesh.morphs().get(1).sparse());
    float[] weights=new float[11];weights[1]=.5f;weights[7]=.5f;
    var result=new cc.sirrus.ysmlib.scene.java.Deformer().deform(mesh,List.of(Matrix4.IDENTITY),new FloatData(weights),cc.sirrus.ysmlib.scene.java.Deformer.NormalMode.MMD_WEIGHTED_ROTATION);
    assertEquals(2.5f,result.get("POSITION").values().get(6),1e-6);assertEquals(2,result.get("POSITION").values().get(7),1e-6);
    assertEquals(15.125f+3.125f*.5f,result.get("_MMD_UV4").values().get(2*4+3),1e-6);
    assertEquals(15.125f,result.get("_MMD_UV4").values().get(3),1e-6);
  }
}
