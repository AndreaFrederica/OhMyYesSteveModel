package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YsmSkeletonBindingTest {
    ScenePackageAssets model()throws Exception {
        ByteData data;
        try(var input=getClass().getResourceAsStream("/mmd-oracle/skin.pmx")){data=new ByteData(Objects.requireNonNull(input).readAllBytes());}
        return YsmRuntime.scenes().loadPackage(new ScenePackage(new ScenePackage.Source("model","skin.pmx",ScenePackage.Format.PMX),
            new ScenePackage.Settings(.08,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),List.of(),List.of(),Map.of("skin.pmx",data)),ReadLimits.DEFAULT);
    }
    @Test void heldSocketsFollowEvaluatedBonesInMetersWithoutMirroringItems()throws Exception {
        var asset=model();var skeleton=SceneSkeleton.of(asset);
        var socket=new SceneModelProfile.HandSocket(true,"leftHand",.1,.2,.3,0,0,0,2);
        var profile=SceneModelProfile.defaults(.08,20,0).withBones(Map.of("leftHand",SceneSkeleton.bind(skeleton,0)))
            .withHeldItems(new SceneModelProfile.HeldItems(true,SceneModelProfile.FirstPersonItems.MODEL,socket,SceneModelProfile.HandSocket.defaults("rightHand")));
        try(var player=YsmRuntime.scenes().playback(asset,ScenePackagePlayback.Selection.REST,ScenePackagePlayback.Settings.previewUi().withPhysics(false),ReadLimits.DEFAULT)) {
            player.bonePoses(Map.of(0,new Pose(new Vec3(2,3,4),Rotation.axisAngle(new Vec3(0,0,1),Math.PI/2))));
            var frame=player.seek(0);var bone=((ScenePackagePlayback.Mmd)frame.details()).value().pose().bones().get(0);
            var matrix=SceneHandSockets.resolve(frame,profile,true).orElseThrow();
            var conversion=SceneHandSockets.coordinates(frame.thirdPerson().coordinates());
            var expected=conversion.transformPoint(bone.position()).add(new Vec3(-.2f,.1f,.3f));
            var actual=matrix.transformPoint(Vec3.ZERO);
            assertEquals(expected.x(),actual.x(),1e-5);assertEquals(expected.y(),actual.y(),1e-5);assertEquals(expected.z(),actual.z(),1e-5);
            var x=matrix.transformDirection(new Vec3(1,0,0));var y=matrix.transformDirection(new Vec3(0,1,0));var z=matrix.transformDirection(new Vec3(0,0,1));
            assertEquals(2,x.y(),1e-5);assertEquals(-2,y.x(),1e-5);assertEquals(2,z.z(),1e-5,"Left-handed source must not mirror item Z");
            assertTrue(SceneHandSockets.resolve(frame,profile,false).isEmpty());
            var restored=YsmRuntime.scenes().readModelProfile(YsmRuntime.scenes().writeModelProfile(profile));assertEquals(profile,restored);
        }
    }
    @Test void evaluatedYsmPoseFeedsProductionMmdPlayerAndReleasesCleanly()throws Exception {
        var asset=model();var skeleton=SceneSkeleton.of(asset);
        var profile=SceneModelProfile.defaults(.08,20,0).withBones(Map.of("root",SceneSkeleton.bind(skeleton,0)))
            .withRetarget(new SceneModelProfile.Retarget(Map.of("root","Root"),2));
        var binding=new YsmSkeletonBinding(List.of(new YsmSkeletonBinding.Bone("Root",-1,Vec3.ZERO,Vec3.ZERO)),asset,profile);
        float[] attributes=new float[14];attributes[6]=attributes[7]=attributes[8]=1;
        attributes[3]=1;attributes[5]=2;attributes[1]=.4f;
        var poses=binding.evaluate(attributes);
        assertEquals(new Vec3(-2,0,-4),poses.get(0).position());
        var expected=Rotation.axisAngle(new Vec3(0,1,0),-.4);
        assertEquals(expected.y(),poses.get(0).rotation().y(),1e-6);
        try(var player=YsmRuntime.scenes().playback(asset,ScenePackagePlayback.Selection.REST,ScenePackagePlayback.Settings.previewUi().withPhysics(false),ReadLimits.DEFAULT)) {
            var rest=player.seek(0);
            player.bonePoses(poses);player.ikOverrides(binding.ikOverrides());var frame=player.seek(0);
            var pose=((ScenePackagePlayback.Mmd)frame.details()).value().pose();
            assertEquals(skeleton.get(0).position().add(poses.get(0).position()),pose.bones().get(0).position());
            assertNotEquals(rest.thirdPerson(),frame.thirdPerson());
            player.seek(1);assertEquals(frame,player.seek(0));
            player.bonePoses(Map.of());player.ikOverrides(Map.of());assertEquals(rest,player.seek(0));
            assertThrows(IllegalArgumentException.class,()->player.bonePoses(Map.of(999999,Pose.IDENTITY)));
            assertEquals(rest,player.seek(0));
        }
        attributes[6]=2;
        assertThrows(UnsupportedOperationException.class,()->binding.evaluate(attributes));
    }
}
