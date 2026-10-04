package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;
import static org.junit.jupiter.api.Assertions.*;

class MmdRigTest {
  static final Vec3 X=new Vec3(1,0,0),Y=new Vec3(0,1,0),Z=new Vec3(0,0,1);
  static Names names(String name) { return new Names(name,"en-"+name); }
  static Bone bone(String name,Vec3 p,int parent,int layer,int flags,Inherit inherit,Vec3 fixed,Integer external,Ik ik) {
    return new Bone(names(name),p,parent,layer,flags,-1,Y,inherit,fixed,null,external,ik);
  }
  static Bone bone(String name,Vec3 p,int parent,int layer) { return bone(name,p,parent,layer,0x1e,null,null,null,null); }
  static PmxDocument model(List<Bone> bones,List<Morph> morphs,List<Material> materials) {
    return new PmxDocument(2.1f,new ByteData(new byte[8]),names("test"),"","",List.of(),new IntData(),List.of(),materials,bones,morphs,List.of(),List.of(),List.of(),List.of(),new ByteData(new byte[0]));
  }
  static AnimationFrame.Channel channel(AnimationClip.Property property,String name,float... values) { return new AnimationFrame.Channel(property,-1,name,"",new FloatData(values)); }
  static AnimationFrame frame(AnimationFrame.Channel... channels) { return new AnimationFrame(0,List.of(channels)); }
  static void vector(Vec3 expected,Vec3 actual) { assertEquals(expected.x(),actual.x(),2e-5);assertEquals(expected.y(),actual.y(),2e-5);assertEquals(expected.z(),actual.z(),2e-5); }

  @Test void ccdReachesGoalAndVmdIkSwitchDisablesIt() {
    var bones=List.of(bone("hip",Vec3.ZERO,-1,0),bone("knee",Y,0,1),bone("foot",new Vec3(0,2,0),1,2),
        bone("ik",new Vec3(1,1,0),-1,3,0x3e,null,null,null,new Ik(2,80,.4f,List.of(new IkLink(1,new Vec3(0,0,-3.14f),Vec3.ZERO),new IkLink(0,null,null)))));
    var evaluator=new MmdEvaluator(model(bones,List.of(),List.of()));var pose=evaluator.evaluate(frame());
    vector(new Vec3(1,1,0),pose.bones().get(2).position());
    var knee=pose.bones().get(0).rotation().inverse().multiply(pose.bones().get(1).rotation());assertTrue(knee.z()<=0);
    vector(new Vec3(0,2,0),evaluator.evaluate(frame(channel(AnimationClip.Property.IK_ENABLED,"ik",0))).bones().get(2).position());
    vector(new Vec3(1,1,0),evaluator.evaluate(frame()).bones().get(2).position());
  }

  @Test void antipodalIkAndZeroLengthLinksStayFinite() {
    var bones=List.of(bone("root",Vec3.ZERO,-1,0),bone("tip",Y,0,1),
        bone("goal",Y.multiply(-1),-1,2,0x3e,null,null,null,new Ik(1,20,.5f,List.of(new IkLink(1,null,null),new IkLink(0,null,null)))));
    vector(Y.multiply(-1),new MmdEvaluator(model(bones,List.of(),List.of())).evaluate(frame()).bones().get(1).position());
  }

  @Test void fixedAxisMappingPrecedesBoneMorphAndLocalAxesDoNotReinterpretVmd() {
    var b=bone("root",Vec3.ZERO,-1,0,0x41e,null,X,null,null);
    var offset=Rotation.axisAngle(Z,Math.PI/2);
    var morph=new Morph(names("turn"),4,2,List.of(new BoneOffset(0,new Vec3(2,0,0),new FloatData(offset.x(),offset.y(),offset.z(),offset.w()))));
    var motion=Rotation.axisAngle(Y,Math.PI/2);
    var pose=new MmdEvaluator(model(List.of(b),List.of(morph),List.of())).evaluate(frame(
        channel(AnimationClip.Property.ROTATION,"root",motion.x(),motion.y(),motion.z(),motion.w()),channel(AnimationClip.Property.MORPH_WEIGHTS,"turn",1)));
    vector(new Vec3(2,0,0),pose.bones().get(0).position());vector(Z,pose.bones().get(0).rotation().rotate(Y));
  }

  @Test void appendLocalUsesGlobalDeltaAndNonlocalUsesAnimationWithNestedAppend() {
    var bones=List.of(bone("root",Vec3.ZERO,-1,0),bone("child",Y,0,1),
        bone("global",Vec3.ZERO,-1,2,0x39e,new Inherit(1,1),null,null,null),
        bone("local",Vec3.ZERO,-1,3,0x31e,new Inherit(1,1),null,null,null),
        bone("nested",Vec3.ZERO,-1,4,0x31e,new Inherit(3,.5f),null,null,null));
    var q=Rotation.axisAngle(Z,Math.PI/2);
    var pose=new MmdEvaluator(model(bones,List.of(),List.of())).evaluate(frame(
        channel(AnimationClip.Property.ROTATION,"root",q.x(),q.y(),q.z(),q.w()),channel(AnimationClip.Property.TRANSLATION,"child",0,1,0),
        channel(AnimationClip.Property.TRANSLATION,"local",2,0,0)));
    vector(new Vec3(-2,-1,0),pose.bones().get(2).position());vector(new Vec3(2,1,0),pose.bones().get(3).position());
    vector(new Vec3(1,.5f,0),pose.bones().get(4).position());vector(Y,pose.bones().get(2).rotation().rotate(X));
  }

  @Test void afterPhysicsAndOutsideParentsAreExplicitStages() {
    var bones=List.of(bone("root",Vec3.ZERO,-1,0),bone("after",Y,0,1,0x101e,null,null,7,null));
    var source=model(bones,List.of(),List.of());var rig=new MmdRig(source);
    rig.begin(frame(channel(AnimationClip.Property.DISPLAY,"",0)),Map.of(7,new Pose(new Vec3(0,0,3),Rotation.IDENTITY)));
    rig.update(false);rig.applyPhysics(Map.of(0,new Pose(new Vec3(2,0,0),Rotation.axisAngle(Z,Math.PI/2))));rig.update(true);
    vector(new Vec3(1,0,3),rig.pose().bones().get(1).position());assertFalse(rig.pose().visible());
    assertEquals(1,new MmdEvaluator(source).evaluate(frame()).diagnostics().size());
  }

  @Test void missingDuplicateAndUniversalNamesProduceDeterministicBindingDiagnostics() {
    var model=model(List.of(bone("same",Vec3.ZERO,-1,0),bone("same",Y,-1,1)),List.of(),List.of());
    var pose=new MmdEvaluator(model).evaluate(frame(channel(AnimationClip.Property.TRANSLATION,"en-same",2,0,0),channel(AnimationClip.Property.IK_ENABLED,"missing",0)));
    assertEquals(2,pose.diagnostics().size());vector(new Vec3(2,0,0),pose.bones().get(0).position());vector(Y,pose.bones().get(1).position());
  }
}
