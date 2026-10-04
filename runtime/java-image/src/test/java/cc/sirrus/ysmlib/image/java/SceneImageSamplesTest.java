package cc.sirrus.ysmlib.image.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneImageSamplesTest {
  final SceneImageSamples samples=new SceneImageSamples();
  @Test void materialTransferPrecedesFilteringAndKeepsLinearDataUnchanged() throws Exception {
    var source=image(SceneImage.Alpha.STRAIGHT,0,0,0,.25f,1,.5f,.04045f,.75f);
    var linear=samples.prepare(source,new SceneImageUsage(SceneImageUsage.Transfer.SRGB,SceneImageUsage.Alpha.STRAIGHT),ReadLimits.DEFAULT);
    assertEquals(.5,(linear.get(0)+linear.get(4))*.5,1e-7,"Black/white color mip sample must be 0.5 linear");
    assertEquals(.21404114,linear.get(5),1e-7);assertEquals(.003130805,linear.get(6),1e-8);
    assertEquals(.25,linear.get(3));assertEquals(.75,linear.get(7));
    assertSame(source.rgba(),samples.prepare(source,new SceneImageUsage(SceneImageUsage.Transfer.LINEAR,SceneImageUsage.Alpha.RAW),ReadLimits.DEFAULT));
    assertEquals(.5,source.rgba().get(5),"Source samples stay immutable when material interpretation differs");
    var legacy=samples.prepare(source,new SceneImageUsage(SceneImageUsage.Transfer.SOURCE_NUMERIC,SceneImageUsage.Alpha.STRAIGHT),ReadLimits.DEFAULT);
    assertEquals(source.rgba(),legacy,"Legacy shader numeric RGB must not acquire a PBR transfer conversion");
  }
  @Test void premultipliedAndUndefinedAttributesDoNotSilentlyBecomeWrongOpacity() throws Exception {
    var source=image(SceneImage.Alpha.PREMULTIPLIED,.25f,.125f,.5f,.5f,0,0,0,0);
    var color=samples.prepare(source,new SceneImageUsage(SceneImageUsage.Transfer.SRGB,SceneImageUsage.Alpha.STRAIGHT),ReadLimits.DEFAULT);
    assertEquals(.21404114,color.get(0),1e-7);assertEquals(1,color.get(2));assertEquals(.5,color.get(3));
    assertEquals(0,color.get(4));assertEquals(0,color.get(7));
    var undefined=image(SceneImage.Alpha.UNDEFINED,.1f,.2f,.3f,.4f);
    assertThrows(IOException.class,()->samples.prepare(undefined,new SceneImageUsage(SceneImageUsage.Transfer.LINEAR,SceneImageUsage.Alpha.STRAIGHT),ReadLimits.DEFAULT));
    assertEquals(1,samples.prepare(undefined,new SceneImageUsage(SceneImageUsage.Transfer.LINEAR,SceneImageUsage.Alpha.OPAQUE),ReadLimits.DEFAULT).get(3));
    assertThrows(IOException.class,()->samples.prepare(source,new SceneImageUsage(SceneImageUsage.Transfer.LINEAR,SceneImageUsage.Alpha.RAW),new ReadLimits(10,10,1)));
  }
  private SceneImage image(SceneImage.Alpha alpha,float... pixels) {
    return new SceneImage(SceneImage.Format.TGA,pixels.length/4,1,new FloatData(pixels),new IntData(8,8,8,8),alpha,Map.of(),ByteData.EMPTY);
  }
}
