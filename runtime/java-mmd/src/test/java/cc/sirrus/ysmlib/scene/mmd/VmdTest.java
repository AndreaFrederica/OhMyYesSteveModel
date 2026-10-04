package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import cc.sirrus.ysmlib.scene.java.*;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.nio.charset.Charset;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cc.sirrus.ysmlib.scene.AnimationClip.Property.*;

class VmdTest {
  private static void text(ByteBuffer b,String value,int size) { byte[] bytes=value.getBytes(Charset.forName("windows-31j"));b.put(bytes);b.put(new byte[size-bytes.length]); }
  private static void vec(ByteBuffer b,float... values) { for(float v:values) b.putFloat(v); }
  private static ByteData freeze(ByteBuffer b) { return new ByteData(Arrays.copyOf(b.array(),b.position())); }
  private static ByteBuffer header() { var b=ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);text(b,"Vocaloid Motion Data 0002",30);text(b,"テスト",20);return b; }
  private ByteData fixture() {
    var b=header();b.putInt(2);
    for(int frame:new int[]{0,30}) { text(b,"センター",15);b.putInt(frame);vec(b,frame==0?0:8,0,0,0,0,0,1);
      byte[] cp=new byte[64];for(int channel=0;channel<4;channel++) { cp[channel+4]=127;cp[channel+12]=127; }b.put(cp); }
    b.putInt(1);text(b,"笑い",15);b.putInt(15).putFloat(.7f);
    b.putInt(3);
    for(int frame:new int[]{0,1,31}) { b.putInt(frame).putFloat(-10);vec(b,frame,0,0,0,0,0);byte[] cp=new byte[24];
      for(int i=0;i<6;i++) { cp[i*4+1]=127;cp[i*4+3]=127; }b.put(cp);b.putInt(45).put((byte)0); }
    b.putInt(2);b.putInt(0);vec(b,1,0,0,-1,-2,-3);b.putInt(30);vec(b,0,1,0,-3,-4,-5);
    b.putInt(2);b.putInt(0).put((byte)1).putFloat(.04f);b.putInt(30).put((byte)2).putFloat(.06f);
    b.putInt(1);b.putInt(0).put((byte)1).putInt(1);text(b,"左足ＩＫ",20);b.put((byte)0);
    return freeze(b);
  }
  @Test void fullVmdPreservesAndEvaluatesEverySection() throws Exception {
    var source=fixture();var document=new VmdReader().read(source,ReadLimits.DEFAULT);assertEquals(source,document.source());
    assertEquals(6,document.sections());assertEquals("センター",document.bones().get(0).name());assertEquals("左足ＩＫ",document.properties().get(0).ik().get(0).name());
    var clip=new VmdAnimation().compile(document).clip();var sampler=new ClipSampler();
    var frame=sampler.sample(clip,.125);
    assertEquals(7,value(frame,TRANSLATION).get(0),1e-5);
    assertEquals(0,value(sampler.sample(clip,1/60.0),CAMERA_TARGET).get(0),1e-6);
    assertEquals(1,value(sampler.sample(clip,1/30.0),CAMERA_TARGET).get(0),1e-6);
    var middle=sampler.sample(clip,.5);
    assertArrayEquals(new float[]{.5f,.5f,0},value(middle,LIGHT_COLOR).copy(),1e-6f);
    assertArrayEquals(new float[]{-2,-3,-4},value(middle,LIGHT_DIRECTION).copy(),1e-6f);
    assertEquals(.05f,value(middle,SHADOW_DISTANCE).get(0),1e-6);
    assertEquals(1,value(middle,SHADOW_MODE).get(0));assertEquals(2,value(sampler.sample(clip,1),SHADOW_MODE).get(0));
    assertEquals(0,value(middle,IK_ENABLED).get(0));assertEquals(1,value(middle,DISPLAY).get(0));
    assertEquals(Math.PI/4,value(middle,CAMERA_FOV).get(0),1e-6);
    // Non-monotonic sampling is independent of evaluation history.
    assertEquals(value(frame,TRANSLATION),value(sampler.sample(clip,.125),TRANSLATION));
  }
  private FloatData value(AnimationFrame frame,AnimationClip.Property property) { return frame.channels().stream().filter(c->c.property()==property).findFirst().orElseThrow().value(); }
  @Test void legacyTailMayBeAbsentButPartialRecordsAreRejected() throws Exception {
    var b=header();b.putInt(0);var minimal=freeze(b);assertEquals(1,new VmdReader().read(minimal,ReadLimits.DEFAULT).sections());
    b.put((byte)1);assertThrows(AssetFormatException.class,()->new VmdReader().read(freeze(b),ReadLimits.DEFAULT));
    var bytes=fixture().copy();assertThrows(AssetFormatException.class,()->new VmdReader().read(new ByteData(Arrays.copyOf(bytes,bytes.length-1)),ReadLimits.DEFAULT));
  }
  @Test void countsAndMalformedTextFailBeforeAllocation() {
    var b=header();b.putInt(-1);assertThrows(AssetFormatException.class,()->new VmdReader().read(freeze(b),ReadLimits.DEFAULT));
    var bytes=fixture().copy();bytes[54]=(byte)0x81;bytes[55]=0;
    assertThrows(AssetFormatException.class,()->new VmdReader().read(new ByteData(bytes),ReadLimits.DEFAULT));
    assertThrows(AssetFormatException.class,()->new VmdReader().read(fixture(),new ReadLimits(100,100,100)));
  }
  @Test void duplicateSourceKeysAreRetainedAndReported() throws Exception {
    var d=new VmdReader().read(fixture(),ReadLimits.DEFAULT);var bones=new ArrayList<>(d.bones());bones.add(d.bones().get(0));
    var duplicate=new VmdDocument(d.signature(),d.modelName(),bones,d.morphs(),d.cameras(),d.lights(),d.shadows(),d.properties(),d.sections(),d.source());
    var result=new VmdAnimation().compile(duplicate);assertEquals(3,duplicate.bones().size());assertEquals(2,result.compatibility().diagnostics().size());
  }
}
