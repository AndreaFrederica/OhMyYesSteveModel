package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;
import static cc.sirrus.ysmlib.scene.mmd.MmdRigTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MmdMorphTest {
  static Morph morph(String name,int type,MorphOffset... offsets) { return new Morph(names(name),4,type,List.of(offsets)); }
  @Test void nestedGroupWeightsAccumulateAndOrderedBoneOffsetsAreNotCollapsed() {
    var q=Rotation.axisAngle(Z,Math.PI/2);var packed=new FloatData(q.x(),q.y(),q.z(),q.w());
    var morphs=List.of(morph("bone",2,new BoneOffset(0,X,packed)),morph("group",0,new GroupOffset(0,.5f)),morph("outer",0,new GroupOffset(1,.8f)));
    var source=model(List.of(bone("root",Vec3.ZERO,-1,0)),morphs,List.of());
    var state=new MmdMorphEvaluator(source).evaluate(new FloatData(.25f,.5f,1));
    assertEquals(.9f,state.meshWeights().get(0),1e-6);vector(new Vec3(.9f,0,0),state.boneTranslations().get(0));
    // Three visits at .25, .25 and .4: 90 * (1 - .75 * .75 * .6) degrees.
    var expected=Rotation.axisAngle(Z,Math.PI/2*(1-.75*.75*.6));vector(expected.rotate(X),state.boneRotations().get(0).rotate(X));
    assertThrows(IllegalArgumentException.class,()->new MmdMorphEvaluator(source,2).evaluate(new FloatData(0,0,1)));
  }
  @Test void flipThresholdsSelectAuthoredChildWeightsAndDoNotLeakBetweenEvaluations() {
    var morphs=List.of(morph("a",1),morph("b",1),morph("c",1),
        morph("flip",9,new GroupOffset(0,.1f),new GroupOffset(1,.6f),new GroupOffset(2,.9f)),morph("control",0,new GroupOffset(3,1)));
    var evaluator=new MmdMorphEvaluator(model(List.of(),morphs,List.of()));
    assertEquals(.1f,evaluator.evaluate(new FloatData(0,0,0,0,.24f)).meshWeights().get(0));
    assertEquals(.6f,evaluator.evaluate(new FloatData(0,0,0,0,.5f)).meshWeights().get(1));
    assertEquals(.9f,evaluator.evaluate(new FloatData(0,0,0,0,.99f)).meshWeights().get(2));
    assertArrayEquals(new float[5],evaluator.evaluate(new FloatData(0,0,0,0,0)).meshWeights().copy());
  }
  @Test void materialMultiplyAddAllMaterialsAndTextureChannelsRemainSeparate() {
    var material=new Material(names("mat"),new FloatData(.8f,.5f,.4f,1),new Vec3(.3f,.4f,.5f),32,new Vec3(.1f,.2f,.3f),31,new FloatData(0,0,0,1),2,-1,-1,0,true,0,"",0);
    var multiply=new MaterialOffset(-1,0,new FloatData(2,2,2,1),Vec3.ONE,1,Vec3.ONE,new FloatData(1,1,1,1),1,new FloatData(2,3,4,1),new FloatData(1,1,1,1),new FloatData(1,1,1,1));
    var add=new MaterialOffset(0,1,new FloatData(.2f,0,0,0),Vec3.ZERO,0,Vec3.ZERO,new FloatData(0,0,0,0),0,new FloatData(.4f,0,0,0),new FloatData(0,0,0,0),new FloatData(0,0,0,0));
    var state=new MmdMorphEvaluator(model(List.of(),List.of(morph("mul",8,multiply),morph("add",8,add)),List.of(material,material))).evaluate(new FloatData(.5f,.25f));
    assertEquals(1.25f,state.materials().get(0).diffuse().get(0),1e-6);assertEquals(1.2f,state.materials().get(1).diffuse().get(0),1e-6);
    assertEquals(1.5f,state.materials().get(0).textureMultiply().get(0));assertEquals(.1f,state.materials().get(0).textureAdd().get(0));
    assertEquals(2,state.materials().get(0).edgeSize());
  }
  @Test void impulsesAreOwnedCommandsIncludingExplicitReset() {
    var source=model(List.of(),List.of(morph("impulse",10,new ImpulseOffset(2,true,X,Z),new ImpulseOffset(3,false,Vec3.ZERO,Vec3.ZERO))),List.of());
    var state=new MmdMorphEvaluator(source).evaluate(new FloatData(.5f));assertEquals(2,state.impulses().size());
    vector(X.multiply(.5f),state.impulses().get(0).velocity());assertTrue(state.impulses().get(0).local());assertTrue(state.impulses().get(1).reset());
  }
}
