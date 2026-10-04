package cc.sirrus.ysmlib.scene.mmd;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.ClipSampler;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cc.sirrus.ysmlib.scene.mmd.MmdRigTest.*;
import static org.junit.jupiter.api.Assertions.*;
class MmdBasicAnimationFactoryTest {
    @Test void fullWidthIkNamesDoNotBecomeLegs() {
        var source=model(List.of(bone("左足",Y,-1,0),bone("左足ＩＫ",Vec3.ZERO,-1,0),bone("右足ＩＫ",Vec3.ZERO,-1,0)),List.of(),List.of());
        var tracks=MmdBasicAnimationFactory.forState(source,"walk").tracks();
        assertEquals(List.of("左足"),tracks.stream().filter(t->t.property()==AnimationClip.Property.ROTATION).map(AnimationClip.Track::binding).toList());
        assertEquals(2,tracks.stream().filter(t->t.property()==AnimationClip.Property.IK_ENABLED).count());
    }
    @Test void explicitMappingReplacesAutomaticBoneAndUsesCorrectionAxis() {
        var source=model(List.of(bone("左足",Y,-1,0),bone("customLeg",Y,-1,0)),List.of(),List.of());
        var map=Map.of("leftUpperLeg",new SceneModelProfile.BoneBinding(1,"customLeg","",0,0,90,1));
        var clip=MmdBasicAnimationFactory.forState(source,"walk",map);
        var pose=new MmdEvaluator(source).evaluate(new ClipSampler().sample(clip,.25));
        assertEquals(Rotation.IDENTITY,pose.bones().get(0).rotation());
        assertTrue(Math.abs(pose.bones().get(1).rotation().y())>.1);
        assertEquals(0,pose.bones().get(1).rotation().x(),1e-5);
    }
    @Test void walkingMovesActualBonesAndDoesNotRotateHelpersOrRoot() {
        var source=model(List.of(bone("root",Vec3.ZERO,-1,0),bone("左足",Y,0,0),bone("左ひざ",Y.multiply(2),1,0),
            bone("右足",Y,0,0),bone("左足IK",Vec3.ZERO,0,0),bone("左腕",Y,0,0)),List.of(),List.of());
        var clip=MmdBasicAnimationFactory.forState(source,"walk");var sampler=new ClipSampler();
        var rest=new MmdEvaluator(source).evaluate(sampler.sample(clip,0));
        var quarter=new MmdEvaluator(source).evaluate(sampler.sample(clip,.25));
        assertNotEquals(rest.bones().get(2).position(),quarter.bones().get(2).position());
        assertTrue(Math.abs(quarter.bones().get(1).rotation().x())>.1);
        assertTrue(quarter.bones().get(1).rotation().x()*quarter.bones().get(3).rotation().x()<0);
        assertTrue(quarter.bones().get(1).rotation().x()*quarter.bones().get(5).rotation().x()<0);
        assertTrue(clip.tracks().stream().noneMatch(t->t.property()==AnimationClip.Property.ROTATION && Set.of("root","左足IK","左ひざ").contains(t.binding())));
        assertTrue(clip.tracks().stream().anyMatch(t->t.property()==AnimationClip.Property.IK_ENABLED && t.binding().equals("左足IK")));
    }
}
