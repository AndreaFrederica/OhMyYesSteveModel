package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.nio.charset.Charset;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PmdTest {
  private static void text(ByteBuffer b,String text,int size) { var bytes=text.getBytes(Charset.forName("windows-31j"));b.put(bytes).put(new byte[size-bytes.length]); }
  private static void f(ByteBuffer b,float... values) { for(float v:values) b.putFloat(v); }
  private static ByteData fixture(boolean extended) {
    var b=ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);text(b,"Pmd",3);b.putFloat(1);text(b,"モデル",20);text(b,"permission",256);
    b.putInt(3);for(int i=0;i<3;i++) { f(b,i,0,0,0,1,0,0,0);b.putShort((short)0).putShort((short)0).put((byte)50).put((byte)0); }
    b.putInt(3).putShort((short)0).putShort((short)1).putShort((short)2);
    b.putInt(1);f(b,1,1,1,1,3,0,0,0,.5f,.5f,.5f);b.put((byte)2).put((byte)1).putInt(3);text(b,"顔.png*env.sph",20);
    b.putShort((short)1);text(b,"センター",20);b.putShort((short)-1).putShort((short)0).put((byte)1).putShort((short)0);f(b,0,5,0);
    b.putShort((short)1).putShort((short)0).putShort((short)0).put((byte)1).putShort((short)40).putFloat(.25f).putShort((short)0);
    b.putShort((short)2);text(b,"base",20);b.putInt(1).put((byte)0).putInt(2);f(b,7,0,0);
    text(b,"笑い",20);b.putInt(1).put((byte)3).putInt(0);f(b,0,1,0);
    b.put((byte)1).putShort((short)1).put((byte)1);text(b,"体",50);b.putInt(1).putShort((short)0).put((byte)1);
    if(extended) {
      b.put((byte)1);text(b,"model",20);text(b,"permission-en",256);text(b,"center",20);text(b,"smile",20);text(b,"body",50);
      for(int i=0;i<10;i++) text(b,"toon"+(i+1)+".bmp",100);
      b.putInt(1);text(b,"physics",20);b.putShort((short)0).put((byte)1).putShort((short)0x1357).put((byte)0);f(b,1,0,0,0,2,0,0,0,0,1,.1f,.2f,.3f,.4f);b.put((byte)2);
      b.putInt(1);text(b,"spring",20);b.putInt(0).putInt(-1);for(int i=0;i<24;i++) b.putFloat(i);
    }
    return new ByteData(Arrays.copyOf(b.array(),b.position()));
  }
  @Test void preservesBaseMorphIndirectionAndOptionalExtensions() throws Exception {
    var reader=new PmdReader();var legacy=reader.read(fixture(false),ReadLimits.DEFAULT);assertEquals(0,legacy.optionalSections());assertNull(legacy.english());
    var d=reader.read(fixture(true),ReadLimits.DEFAULT);assertEquals(3,d.optionalSections());assertEquals("smile",d.english().morphs().get(0));
    assertEquals("顔.png*env.sph",d.materials().get(0).textureNames());assertEquals(2,d.morphs().get(0).vertices().get(0).index());
    assertEquals(0,d.morphs().get(1).vertices().get(0).index());assertEquals(10,d.toonTextures().size());
    assertEquals(5,d.bones().get(0).position().y());assertEquals(2,d.rigidBodies().get(0).position().y());
    assertEquals(0x1357,d.rigidBodies().get(0).collisionMask());assertEquals(23,d.joints().get(0).angularSpring().z());
  }
  @Test void truncationInsideAnExtensionIsRejected() {
    byte[] bytes=fixture(true).copy();assertThrows(AssetFormatException.class,()->new PmdReader().read(new ByteData(Arrays.copyOf(bytes,bytes.length-1)),ReadLimits.DEFAULT));
    bytes[3]=0;bytes[4]=0;bytes[5]=0;bytes[6]=0;assertThrows(AssetFormatException.class,()->new PmdReader().read(new ByteData(bytes),ReadLimits.DEFAULT));
  }
  @Test void meshCompilerUsesBaseCoordinatesAndResolvesMorphIndices() throws Exception {
    var d=new PmdReader().read(fixture(false),ReadLimits.DEFAULT);var mesh=new MmdMeshCompiler().compile(d).primitives().get(0);
    assertEquals(7,mesh.attributes().get("POSITION").values().get(6));assertEquals(2,mesh.morphs().get(1).vertexIndices().get(0));
    var result=new cc.sirrus.ysmlib.scene.java.Deformer().deform(mesh,List.of(Matrix4.IDENTITY),new FloatData(0,.5f),cc.sirrus.ysmlib.scene.java.Deformer.NormalMode.MMD_WEIGHTED_ROTATION);
    assertEquals(.5f,result.get("POSITION").values().get(7));assertEquals(0,result.get("POSITION").values().get(1));
  }
}
