package cc.sirrus.ysmlib.scene.bvh;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BvhTest {
  private static final String MOTION="""
      HIERARCHY
      ROOT root { OFFSET 1 2 3 CHANNELS 6 Xposition Yposition Zposition Zrotation Xrotation Yrotation
        JOINT child { OFFSET 0 2 0 CHANNELS 3 Xrotation Yrotation Zrotation End Site { OFFSET 0 1 0 } }
      }
      MOTION
      Frames: 2
      Frame Time: 0.04
      0 0 0 0 0 0 0 0 0
      2 0 0 90 90 0 0 90 0
      """;
  private static ByteData bytes(String s) { return new ByteData(s.getBytes(StandardCharsets.UTF_8)); }
  private static BvhDocument read(String s) throws Exception { return new BvhReader().read(bytes(s),ReadLimits.DEFAULT); }
  private static void vector(Vec3 expected,Vec3 actual) { assertEquals(expected.x(),actual.x(),2e-5);assertEquals(expected.y(),actual.y(),2e-5);assertEquals(expected.z(),actual.z(),2e-5); }
  @Test void authoredEulerOrderOffsetHierarchyAndEndSites() throws Exception {
    var d=read(MOTION.replace("ROOT root","ROOT rig:root"));assertEquals("rig:root",d.joints().get(0).name());assertEquals(3,d.joints().size());assertTrue(d.joints().get(2).endSite());assertEquals(.04,d.durationSeconds());
    var evaluator=new BvhEvaluator(d,new SceneAsset.Coordinates(true,.01,"Y"));var p=evaluator.evaluate(.04,BvhEvaluation.Sampling.LINEAR_CHANNELS);
    // Rz(90)*Rx(90): child +Y points to +Z; then child Ry(90) leaves its End Site +Y unchanged.
    vector(new Vec3(3,2,3),p.globalMatrices().get(0).transformPoint(Vec3.ZERO));
    vector(new Vec3(3,2,5),p.globalMatrices().get(1).transformPoint(Vec3.ZERO));
    vector(new Vec3(3,2,6),p.globalMatrices().get(2).transformPoint(Vec3.ZERO));
    assertEquals(.01,evaluator.scene().coordinates().metersPerUnit());assertEquals(3,evaluator.scene().nodes().size());
    vector(new Vec3(1,5,3),evaluator.evaluate(-1,BvhEvaluation.Sampling.STEP).globalMatrices().get(2).transformPoint(Vec3.ZERO));
  }
  @Test void preservesFullTurnsAndTranslationIsIndependentOfRotationChannelPlacement() throws Exception {
    var d=read("HIERARCHY ROOT r { OFFSET 0 0 0 CHANNELS 2 Zrotation Xposition } MOTION Frames:2 Frame Time:.1 0 2 360 2");
    var evaluator=new BvhEvaluator(d,SceneAsset.Coordinates.GLTF);
    var matrix=evaluator.evaluate(.05,BvhEvaluation.Sampling.LINEAR_CHANNELS).globalMatrices().get(0);
    vector(new Vec3(-1,0,0),matrix.transformDirection(new Vec3(1,0,0)));vector(new Vec3(2,0,0),matrix.transformPoint(Vec3.ZERO));
    vector(new Vec3(2,0,0),evaluator.evaluate(.05,BvhEvaluation.Sampling.STEP).globalMatrices().get(0).transformPoint(Vec3.ZERO));
    assertEquals(360,d.samples().get(2));
  }
  @Test void rejectsMalformedOrExcessiveMotionAndAcceptsEmptyStaticClip() throws Exception {
    for(String invalid:List.of(MOTION.replace("Frames: 2","Frames: 3"),MOTION+" 1",MOTION.replace("Zrotation Xrotation","Xrotation Xrotation"),
        MOTION.replace("0.04","0"),MOTION.replace("OFFSET 0 1 0","OFFSET NaN 1 0"),MOTION.replace("CHANNELS 3","CHANNELS 7"),MOTION.replace("End Site { OFFSET 0 1 0 }","End Site { }")))
      assertThrows(AssetFormatException.class,()->read(invalid));
    assertThrows(AssetFormatException.class,()->new BvhReader().read(bytes(MOTION),new ReadLimits(4096,10,1024)));
    var empty=read("HIERARCHY ROOT r { OFFSET 1 2 3 CHANNELS 0 } MOTION Frames:0 Frame Time:.04");
    vector(new Vec3(1,2,3),new BvhEvaluator(empty,SceneAsset.Coordinates.GLTF).evaluate(1,BvhEvaluation.Sampling.LINEAR_CHANNELS).globalMatrices().get(0).transformPoint(Vec3.ZERO));
  }
  @Test void deepHierarchyDoesNotUseJavaCallStackAndDuplicateNamesRemainDistinct() throws Exception {
    var text=new StringBuilder("HIERARCHY ROOT same { OFFSET 0 1 0 CHANNELS 0 ");
    for(int i=1;i<2000;i++) text.append("JOINT same { OFFSET 0 1 0 CHANNELS 0 ");
    text.append("} ".repeat(2000)).append("MOTION Frames:1 Frame Time:.04");
    var d=read(text.toString());var evaluator=new BvhEvaluator(d,SceneAsset.Coordinates.GLTF);
    vector(new Vec3(0,2000,0),evaluator.evaluate(0,BvhEvaluation.Sampling.STEP).globalMatrices().get(1999).transformPoint(Vec3.ZERO));
  }
}
