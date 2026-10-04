package cc.sirrus.ysmlib.scene;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkeletonRetargeterTest {
    @Test void arbitraryBindingsShareSourceAndUnboundIntermediateBonesKeepTheirLocalRest() {
        var source=List.of(new SkeletonRetargeter.Bone("Control",-1,Pose.IDENTITY),new SkeletonRetargeter.Bone("Tail",0,Pose.IDENTITY));
        var target=List.of(new SkeletonRetargeter.Bone("Base",-1,Pose.IDENTITY),
            new SkeletonRetargeter.Bone("Middle",0,new Pose(new Vec3(0,3,0),Rotation.IDENTITY)),
            new SkeletonRetargeter.Bone("Tip",1,new Pose(new Vec3(0,2,0),Rotation.IDENTITY)));
        var profile=SceneModelProfile.defaults(1,5,0).withBones(Map.of(
            "bone:tailBase",new SceneModelProfile.BoneBinding(0,"Base","",0,0,0,.5),
            "bone:tailTip",new SceneModelProfile.BoneBinding(2,"Tip","0:Base/1:Middle",0,0,0,1)))
            .withRetarget(new SceneModelProfile.Retarget(Map.of("bone:tailBase","Tail","bone:tailTip","Tail"),1));
        var rotation=Rotation.axisAngle(new Vec3(0,1,0),1);
        var pose=new SkeletonRetargeter(source,target,profile).apply(List.of(new Pose(Vec3.ZERO,rotation),Pose.IDENTITY));
        assertFalse(pose.containsKey(1),"Unbound intermediate remains under target hierarchy ownership");
        var actual=pose.get(0).rotation().multiply(pose.get(2).rotation()).rotate(new Vec3(1,0,0));
        var expected=rotation.rotate(new Vec3(1,0,0));
        assertEquals(expected.x(),actual.x(),1e-6);assertEquals(expected.z(),actual.z(),1e-6);
        assertEquals(Vec3.ZERO,pose.get(0).position());assertEquals(Vec3.ZERO,pose.get(2).position());
    }
    @Test void translationsCrossUnboundControlsAndNestedTargetRootsWithoutDoubleApplication(){
        var source=List.of(new SkeletonRetargeter.Bone("Control",-1,Pose.IDENTITY),
            new SkeletonRetargeter.Bone("Hips",0,new Pose(new Vec3(0,2,0),Rotation.IDENTITY)),
            new SkeletonRetargeter.Bone("Hand",1,new Pose(new Vec3(2,0,0),Rotation.IDENTITY)));
        var target=List.of(new SkeletonRetargeter.Bone("All",-1,Pose.IDENTITY),
            new SkeletonRetargeter.Bone("Hips",0,new Pose(new Vec3(0,10,0),Rotation.IDENTITY)),
            new SkeletonRetargeter.Bone("Hand",1,new Pose(new Vec3(5,0,0),Rotation.IDENTITY)));
        var p=SceneModelProfile.defaults(1,20,0).withBones(Map.of(
            "hips",new SceneModelProfile.BoneBinding(1,"Hips","0:All",0,0,0,1),
            "leftHand",new SceneModelProfile.BoneBinding(2,"Hand","0:All/1:Hips",0,0,0,1)))
            .withRetarget(new SceneModelProfile.Retarget(Map.of("hips","Hips","leftHand","Hand"),3));
        var evaluator=new SkeletonRetargeter(source,target,p);
        var pose=evaluator.apply(List.of(new Pose(new Vec3(0,1,0),Rotation.IDENTITY),source.get(1).restLocal(),new Pose(new Vec3(3,0,0),Rotation.IDENTITY)));
        assertEquals(new Vec3(0,3,0),pose.get(1).position());
        assertEquals(new Vec3(3,0,0),pose.get(2).position());
        assertEquals(Vec3.ZERO,evaluator.apply(source.stream().map(SkeletonRetargeter.Bone::restLocal).toList()).get(2).position());
    }
    @Test void restAxisCorrectionAndZeroWeightAreExplicit(){
        var rest=Rotation.axisAngle(new Vec3(0,1,0),.4);
        var source=List.of(new SkeletonRetargeter.Bone("Head",-1,new Pose(Vec3.ZERO,rest)));
        var target=List.of(new SkeletonRetargeter.Bone("Target",-1,Pose.IDENTITY));
        var p=SceneModelProfile.defaults(1,1,0).withBones(Map.of("head",new SceneModelProfile.BoneBinding(0,"Target","",0,0,90,1)))
            .withRetarget(new SceneModelProfile.Retarget(Map.of("head","Head"),1));
        var neutral=new SkeletonRetargeter(source,target,p).apply(List.of(source.get(0).restLocal())).get(0);
        assertEquals(1,neutral.rotation().w(),1e-5);
        var delta=Rotation.axisAngle(new Vec3(1,0,0),.5);
        var result=new SkeletonRetargeter(source,target,p).apply(List.of(new Pose(Vec3.ZERO,delta.multiply(rest)))).get(0).rotation().rotate(new Vec3(0,0,1));
        var expected=Rotation.axisAngle(new Vec3(0,1,0),.5).rotate(new Vec3(0,0,1));
        assertEquals(expected.x(),result.x(),1e-5);assertEquals(expected.y(),result.y(),1e-5);assertEquals(expected.z(),result.z(),1e-5);
        var disabled=p.withBones(Map.of("head",new SceneModelProfile.BoneBinding(0,"Target","",0,0,0,0)));
        assertTrue(new SkeletonRetargeter(source,target,disabled).apply(List.of(Pose.IDENTITY)).isEmpty());
    }
    @Test void parentControlMotionIsTransferredOnceAndTargetLimbLengthsStayAuthored(){
        var source=List.of(new SkeletonRetargeter.Bone("Root",-1,Pose.IDENTITY),new SkeletonRetargeter.Bone("Control",0,Pose.IDENTITY),new SkeletonRetargeter.Bone("Head",1,new Pose(new Vec3(0,2,0),Rotation.IDENTITY)));
        var target=List.of(new SkeletonRetargeter.Bone("センター",-1,Pose.IDENTITY),new SkeletonRetargeter.Bone("頭",0,new Pose(new Vec3(0,10,0),Rotation.IDENTITY)));
        var profile=SceneModelProfile.defaults(.08,10,0).withBones(Map.of("root",new SceneModelProfile.BoneBinding(0,"センター","",0,0,0,1),"head",new SceneModelProfile.BoneBinding(1,"頭","0:センター",0,0,0,1)))
            .withRetarget(new SceneModelProfile.Retarget(Map.of("root","Root","head","Head"),5));
        var rotation=Rotation.axisAngle(new Vec3(0,1,0),Math.PI/2);var control=Rotation.axisAngle(new Vec3(1,0,0),.3);
        var mapped=new SkeletonRetargeter(source,target,profile).apply(List.of(new Pose(new Vec3(1,0,0),rotation),new Pose(Vec3.ZERO,control),source.get(2).restLocal()));
        assertEquals(new Vec3(5,0,0),mapped.get(0).position());assertEquals(Vec3.ZERO,mapped.get(1).position());
        var actual=mapped.get(1).rotation().rotate(new Vec3(0,0,1));var expected=control.rotate(new Vec3(0,0,1));
        assertEquals(expected.x(),actual.x(),1e-5);assertEquals(expected.y(),actual.y(),1e-5);assertEquals(expected.z(),actual.z(),1e-5);
    }
    @Test void invalidHierarchyAndMissingSourceFail(){
        var bad=List.of(new SkeletonRetargeter.Bone("cycle",0,Pose.IDENTITY));
        assertThrows(IllegalArgumentException.class,()->new SkeletonRetargeter(bad,List.of(),SceneModelProfile.defaults(1,1,0)));
        var rig=List.of(new SkeletonRetargeter.Bone("Root",-1,Pose.IDENTITY));
        var p=SceneModelProfile.defaults(1,1,0).withRetarget(new SceneModelProfile.Retarget(Map.of("head","Missing"),1));
        assertThrows(IllegalArgumentException.class,()->new SkeletonRetargeter(rig,rig,p));
    }
}
