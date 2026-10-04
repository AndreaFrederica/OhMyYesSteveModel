package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import cc.sirrus.ysmlib.scene.java.ClipSampler;
import java.nio.charset.Charset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VpdTest {
  private static final String POSE="""
      Vocaloid Pose Data file

      人物.osm; // parent filename
      1;
      Bone0{センター
        1,2,3;
        0,0,0,1;
      }
      Morph0{笑い
        0.75;
      }
      """;
  private ByteData bytes(String text) { return new ByteData(text.getBytes(Charset.forName("windows-31j"))); }
  @Test void poseAndMmmMorphExtensionBecomeConstantTracks() throws Exception {
    var reader=new VpdReader();var d=reader.read(bytes(POSE),ReadLimits.DEFAULT);assertEquals("人物.osm",d.parentFile());
    assertEquals("センター",d.bones().get(0).name());assertEquals(.75f,d.morphs().get(0).weight());
    var frame=new ClipSampler().sample(reader.animation(d),300);assertEquals(3,frame.channels().size());
    assertArrayEquals(new float[]{1,2,3},frame.channels().get(0).value().copy());
    assertEquals(.75f,frame.channels().get(2).value().get(0));
  }
  @Test void malformedAndUnknownEntriesAreNotSilentlyDiscarded() {
    var reader=new VpdReader();
    assertThrows(AssetFormatException.class,()->reader.read(bytes(POSE.replace("1;","2;")),ReadLimits.DEFAULT));
    assertThrows(AssetFormatException.class,()->reader.read(bytes(POSE.replace("Morph0", "Unsupported0")),ReadLimits.DEFAULT));
    assertThrows(AssetFormatException.class,()->reader.read(bytes(POSE.replace("1,2,3", "NaN,2,3")),ReadLimits.DEFAULT));
  }
}
