package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.physics.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cc.sirrus.ysmlib.scene.mmd.MmdRigTest.*;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;
import static org.junit.jupiter.api.Assertions.*;

class MmdOverridesTest {
  private static final PhysicsProvider NO_PHYSICS=new PhysicsProvider() {
    public String id(){throw new AssertionError("No physics needed");}
    public PhysicsWorld createWorld(PhysicsSpec.World settings){throw new AssertionError("No physics needed");}
  };
  static AnimationClip.Track track(AnimationClip.Property property,int index,float... values) {
    return new AnimationClip.Track(property,index,"","",new AnimationCurve(new double[]{0},new FloatData(values),values.length,property==AnimationClip.Property.ROTATION,AnimationCurve.Interpolation.STEP,FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY));
  }
  @Test void vpdReplacesVmdExpressionsOverrideBothAndHeadRotationComposesAfterVpd() {
    var morph=new Morph(names("face"),4,2,List.of(new BoneOffset(0,X,new FloatData(0,0,0,1))));
    var source=model(List.of(bone("root",Vec3.ZERO,-1,0)),List.of(morph),List.of());
    var animation=Rotation.axisAngle(Y,.2);var poseRotation=Rotation.axisAngle(Y,.4);var head=Rotation.axisAngle(Z,.3);
    var clip=new AnimationClip("VMD",List.of(track(AnimationClip.Property.TRANSLATION,0,2,0,0),track(AnimationClip.Property.ROTATION,0,animation.x(),animation.y(),animation.z(),animation.w()),track(AnimationClip.Property.MORPH_WEIGHTS,0,.25f)),30);
    try(var player=new MmdPlayer(source,clip,NO_PHYSICS,MmdPlayback.Settings.preview().withPhysics(false),Map.of())) {
      var original=player.seek(1);vector(new Vec3(2.25f,0,0),original.pose().bones().get(0).position());
      var vpd=new VpdDocument("",List.of(new VpdDocument.BonePose(0,"root",new Vec3(3,0,0),new FloatData(poseRotation.x(),poseRotation.y(),poseRotation.z(),poseRotation.w()))),List.of(new VpdDocument.MorphPose(0,"face",.3f)),ByteData.EMPTY);
      player.vpdPose(vpd);var weights=new HashMap<Integer,Float>();weights.put(0,.75f);player.morphWeights(weights);weights.put(0,0f);player.boneRotations(Map.of(0,head));
      assertSame(original,player.current(),"Setter must not publish an incomplete frame");
      var changed=player.seek(1);vector(new Vec3(3.75f,0,0),changed.pose().bones().get(0).position());vector(poseRotation.multiply(head).rotate(X),changed.pose().bones().get(0).rotation().rotate(X));
      assertEquals(.75f,changed.pose().morphs().meshWeights().get(0));assertEquals(0,changed.simulationSeconds());
      assertThrows(IllegalArgumentException.class,()->player.morphWeights(Map.of(0,Float.NaN)));assertThrows(IllegalArgumentException.class,()->player.ikOverrides(Map.of(3,false)));assertSame(changed,player.seek(1));
      player.vpdPose(null);player.morphWeights(Map.of());player.boneRotations(Map.of());assertEquals(original.pose(),player.seek(1).pose());
    }
  }
  @Test void externalIkOverridesAnimationAndReleasingItRestoresAnimatedSwitch() {
    var bones=List.of(bone("root",Vec3.ZERO,-1,0),bone("tip",Y,0,1),bone("goal",X,-1,2,0x3e,null,null,null,new Ik(1,40,.4f,List.of(new IkLink(0,null,null)))));
    var source=model(bones,List.of(),List.of());var clip=new AnimationClip("IK off",List.of(track(AnimationClip.Property.IK_ENABLED,2,0)),30);
    try(var player=new MmdPlayer(source,clip,NO_PHYSICS,MmdPlayback.Settings.preview().withPhysics(false),Map.of())) {
      vector(Y,player.current().pose().bones().get(1).position());player.ikOverrides(Map.of(2,true));vector(X,player.seek(0).pose().bones().get(1).position());
      player.ikOverrides(Map.of());vector(Y,player.seek(0).pose().bones().get(1).position());player.close();assertThrows(IllegalStateException.class,()->player.morphWeights(Map.of()));
    }
  }
}
