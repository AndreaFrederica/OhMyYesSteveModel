package cc.sirrus.ysmlib.scene;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkeletonReferencePoseTest {
    @Test void idleCalibrationLowersBothArmsAcrossUnboundTwistBonesAndPreservesAnimatedParentMotion() {
        var target=new ArrayList<SceneSkeleton.Bone>();var source=new ArrayList<SkeletonRetargeter.Bone>();
        var sourcePositions=new HashMap<String,Vec3>();var bindings=new LinkedHashMap<String,SceneModelProfile.BoneBinding>();
        var names=new LinkedHashMap<String,String>();
        for(String side:List.of("left","right")) {
            float sign=side.equals("left")?1:-1;int base=target.size(),s=source.size();
            String upper=side+"UpperArm",lower=side+"LowerArm",hand=side+"Hand";
            target.add(new SceneSkeleton.Bone(base,upper,"",-1,new Vec3(sign,4,0),false));
            target.add(new SceneSkeleton.Bone(base+1,"twist"+side,"",base,new Vec3(sign*2,4,0),false));
            target.add(new SceneSkeleton.Bone(base+2,lower,"",base+1,new Vec3(sign*3,4,0),false));
            target.add(new SceneSkeleton.Bone(base+3,hand,"",base+2,new Vec3(sign*5,4,0),false));
            source.add(new SkeletonRetargeter.Bone(upper,-1,new Pose(new Vec3(sign,4,0),Rotation.IDENTITY)));
            source.add(new SkeletonRetargeter.Bone(lower,s,new Pose(new Vec3(0,-1,0),Rotation.IDENTITY)));
            source.add(new SkeletonRetargeter.Bone(hand,s+1,new Pose(new Vec3(0,-1,0),Rotation.IDENTITY)));
            sourcePositions.put(upper,new Vec3(sign,4,0));sourcePositions.put(lower,new Vec3(sign,3,0));sourcePositions.put(hand,new Vec3(sign,2,0));
            for(var entry:Map.of(upper,base,lower,base+2,hand,base+3).entrySet()) {
                bindings.put(entry.getKey(),SceneSkeleton.bind(target,entry.getValue()));names.put(entry.getKey(),entry.getKey());
            }
        }
        var profile=SceneModelProfile.defaults(.08,6,0).withBones(bindings).withRetarget(new SceneModelProfile.Retarget(names,1));
        var calibrated=SkeletonReferencePose.arms(sourcePositions,target,profile);
        var targetRig=target.stream().map(b->new SkeletonRetargeter.Bone(b.name(),b.parent(),new Pose(b.parent()<0?b.position():b.position().subtract(target.get(b.parent()).position()),Rotation.IDENTITY))).toList();
        var mapper=new SkeletonRetargeter(source,targetRig,calibrated);var rest=source.stream().map(SkeletonRetargeter.Bone::restLocal).toList();
        var delta=mapper.apply(rest);var posed=evaluate(targetRig,delta);
        for(int start:new int[]{0,4}) {
            var arm=posed.get(start).position();var elbow=posed.get(start+2).position();var wrist=posed.get(start+3).position();
            assertEquals(arm.x(),wrist.x(),1e-5);assertEquals(arm.y()-2,elbow.y(),1e-5);assertEquals(arm.y()-4,wrist.y(),1e-5);
            assertFalse(delta.containsKey(start+1),"Twist bone retains local ownership");
        }
        var animated=new ArrayList<>(rest);var swing=Rotation.axisAngle(new Vec3(1,0,0),.7);
        animated.set(0,new Pose(rest.get(0).position(),swing));var moved=evaluate(targetRig,mapper.apply(animated));
        Vec3 expected=swing.rotate(new Vec3(0,-4,0));var actual=moved.get(3).position().subtract(moved.get(0).position());
        assertEquals(expected.y(),actual.y(),1e-5);assertEquals(expected.z(),actual.z(),1e-5);
        assertEquals(0,profile.bones().get("leftUpperArm").restRoll(),"Original profile remains unchanged");
    }
    private List<Pose> evaluate(List<SkeletonRetargeter.Bone> bones,Map<Integer,Pose> delta) {
        var out=new ArrayList<Pose>();for(int i=0;i<bones.size();i++) {
            var bone=bones.get(i);var d=delta.getOrDefault(i,Pose.IDENTITY);
            var local=new Pose(bone.restLocal().position().add(d.position()),bone.restLocal().rotation().multiply(d.rotation()));
            out.add(bone.parent()<0?local:out.get(bone.parent()).multiply(local));
        }return out;
    }
}
