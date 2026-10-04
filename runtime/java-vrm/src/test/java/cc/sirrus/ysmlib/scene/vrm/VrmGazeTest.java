package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmFixtures.*;

class VrmGazeTest {
  @Test void rangeMapSupportsZeroRangeAndLegacyNonlinearHermiteCurve() {
    var zero=new VrmDocument.RangeMap(0,3,FloatData.EMPTY);assertEquals(0,VrmGaze.map(zero,0));assertEquals(3,VrmGaze.map(zero,.00001));
    var smooth=new VrmDocument.RangeMap(90,10,new FloatData(0,0,0,0,1,1,0,0));assertEquals(1.5625,VrmGaze.map(smooth,22.5),1e-7);
    assertEquals(10,VrmGaze.map(smooth,120));assertEquals(0,VrmGaze.map(smooth,0));
    var linear=new VrmDocument.RangeMap(90,10,new FloatData(0,0,0,1,1,1,1,0));assertEquals(2.5,VrmGaze.map(linear,22.5),1e-7);
  }
  @Test void expressionsUseCorrectDirectionsAndStillRespectOverride() throws Exception {
    var json=avatar(false);var ext=extension(json,false);
    ext.add("lookAt",JsonParser.parseString("{\"type\":\"expression\",\"offsetFromHeadBone\":[0,0,0]}"));
    ext.add("expressions",JsonParser.parseString("{\"preset\":{\"lookLeft\":{},\"lookRight\":{},\"lookUp\":{},\"lookDown\":{},\"happy\":{\"overrideLookAt\":\"blend\"}}}"));
    var d=read(json);var evaluator=new VrmEvaluator(d);
    var f=evaluator.evaluate(null,0,new VrmEvaluation.Input(Map.of("happy",.5f),Map.of(),new VrmEvaluation.Target(new Vec3(1,-1,1))));
    assertEquals(45,f.gaze().yaw(),1e-6);assertEquals(35.2643897,f.gaze().pitch(),1e-5);
    assertEquals(.25,f.expressions().get("lookLeft"),1e-6);assertEquals(0,f.expressions().get("lookRight"));assertTrue(f.expressions().get("lookDown")>0);
  }
  @Test void boneGazeAccountsForRestAxesAndVrm0FacingDirection() throws Exception {
    for(boolean legacy:List.of(false,true)) {
      var json=avatar(legacy);var ext=extension(json,legacy);
      if(legacy) ext.add("firstPerson",JsonParser.parseString("{\"lookAtTypeName\":\"Bone\",\"firstPersonBoneOffset\":{\"x\":0,\"y\":0,\"z\":0}}"));
      else ext.add("lookAt",JsonParser.parseString("{\"type\":\"bone\",\"offsetFromHeadBone\":[0,0,0]}"));
      var d=read(json);var evaluator=new VrmEvaluator(d);float sign=legacy?-1:1;
      var f=evaluator.evaluate(null,0,new VrmEvaluation.Input(Map.of(),Map.of(),new VrmEvaluation.Target(new Vec3(sign,-1,sign))));
      assertEquals(45,f.gaze().yaw(),1e-4);assertTrue(f.gaze().pitch()>0);
      var eye=f.localRotations().get(d.humanBones().get("leftEye"));Vec3 forward=eye.rotate(new Vec3(0,0,sign));assertTrue(forward.x()*sign>0);assertTrue(forward.y()<0);
    }
    var json=avatar(false);extension(json,false).add("lookAt",JsonParser.parseString("{\"type\":\"bone\",\"offsetFromHeadBone\":[0,0,0]}"));
    var unrotated=read(json);int head=unrotated.humanBones().get("head"),eye=unrotated.humanBones().get("leftEye");
    json.getAsJsonArray("nodes").get(head).getAsJsonObject().add("rotation",JsonParser.parseString("[0,0,0.70710678,0.70710678]"));var d=read(json);
    var evaluated=new VrmEvaluator(d).evaluate(null,0,new VrmEvaluation.Input(Map.of(),Map.of(),new VrmEvaluation.Target(new Vec3(1,0,1))));
    assertEquals(45,evaluated.gaze().yaw(),1e-4);
    Vec3 actual=evaluated.pose().globalMatrices().get(eye).transformDirection(new Vec3(0,0,1));assertEquals(Math.sin(Math.toRadians(5)),actual.x(),2e-6);assertEquals(0,actual.y(),2e-6);
  }
  @Test void ordinaryAnimationOnUnboundMorphTargetsSurvivesExpressionComposition() throws Exception {
    var json=avatar(false);int node=json.getAsJsonArray("nodes").size()-1;
    extension(json,false).add("expressions",JsonParser.parseString("{\"preset\":{\"happy\":{\"morphTargetBinds\":[{\"node\":"+node+",\"index\":0,\"weight\":1}]}}}"));
    var clip=new AnimationClip("morph",List.of(new AnimationClip.Track(AnimationClip.Property.MORPH_WEIGHTS,node,"","",new AnimationCurve(new double[]{0},new FloatData(.2f,.4f,.6f),3,false,AnimationCurve.Interpolation.STEP,FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY))),0);
    var frame=new VrmEvaluator(read(json)).evaluate(clip,0,new VrmEvaluation.Input(Map.of("happy",.7f),Map.of(),null));
    assertArrayEquals(new float[]{.7f,.4f,.6f},frame.pose().morphWeights().get(node).copy());
  }
}
