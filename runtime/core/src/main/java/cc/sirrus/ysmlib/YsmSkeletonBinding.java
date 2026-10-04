package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Portable adapter for already evaluated YSM BoneAttribute values. No controller or Minecraft dependency. */
public final class YsmSkeletonBinding {
    /** YSM authored pixels, radians, and sorted parent indices. */
    public record Bone(String name,int parent,Vec3 pivot,Vec3 restRotation) {}
    private final List<Bone> bones;
    private final SkeletonRetargeter retargeter;
    private final Set<Integer> required;
    private final Map<Integer,Boolean> ik;

    public YsmSkeletonBinding(List<Bone> source,ScenePackageAssets target,SceneModelProfile profile) {
        if(!(target.model() instanceof ScenePackageAssets.Pmx || target.model() instanceof ScenePackageAssets.Pmd))
            throw new IllegalArgumentException("YSM skeleton binding currently requires an MMD target");
        bones=List.copyOf(source);
        var sourceRig=new ArrayList<SkeletonRetargeter.Bone>();
        for(int i=0;i<bones.size();i++) {
            var b=bones.get(i);
            if(b.parent() < -1 || b.parent()>=i)throw new IllegalArgumentException("YSM bones must be in parent-first order");
            sourceRig.add(new SkeletonRetargeter.Bone(b.name(),b.parent(),local(i,Vec3.ZERO,b.restRotation())));
        }
        var skeleton=SceneSkeleton.of(target);SceneSkeleton.validate(skeleton,profile.bones());
        var targetRig=new ArrayList<SkeletonRetargeter.Bone>();
        for(var b:skeleton)targetRig.add(new SkeletonRetargeter.Bone(b.name(),b.parent(),
            new Pose(b.parent()<0?b.position():b.position().subtract(skeleton.get(b.parent()).position()),Rotation.IDENTITY)));
        retargeter=new SkeletonRetargeter(sourceRig,targetRig,profile);
        var needed=new HashSet<Integer>();var driven=new HashSet<Integer>();
        for(var entry:profile.retarget().sourceBones().entrySet()) {
            var binding=profile.bones().get(entry.getKey());if(binding.weight()==0)continue;
            driven.add(binding.index());
            for(int i=0;i<bones.size();i++)if(bones.get(i).name().equals(entry.getValue()))
                for(int p=i;p>=0;p=bones.get(p).parent())needed.add(p);
        }
        required=Set.copyOf(needed);
        var switches=new LinkedHashMap<Integer,Boolean>();
        // An IK chain must not pull explicitly driven FK bones back to the rest controller.
        // Explicitly bound IK controllers retain their own IK solver ownership.
        if(target.model() instanceof ScenePackageAssets.Pmx pmx) {
            for(int i=0;i<pmx.value().bones().size();i++) {
                var chain=pmx.value().bones().get(i).ik();
                if(chain!=null&&!driven.contains(i)&&(driven.contains(chain.target())||chain.links().stream().anyMatch(l->driven.contains(l.bone()))))switches.put(i,false);
            }
        } else for(var chain:((ScenePackageAssets.Pmd)target.model()).value().ik()) {
            boolean touched=driven.contains(chain.target());
            for(int i=0;i<chain.links().size();i++)touched|=driven.contains(chain.links().get(i));
            if(!driven.contains(chain.controller())&&touched)switches.put(chain.controller(),false);
        }
        ik=Map.copyOf(switches);
    }

    /** YSM pose attributes retain their native layout (14 floats/bone). Rotations use exact evaluated values. */
    public Map<Integer,Pose> evaluate(float[] attributes) {
        if(attributes.length!=bones.size()*14)throw new IllegalArgumentException("YSM attribute count differs from bound rig");
        var local=new ArrayList<Pose>(bones.size());
        for(int i=0;i<bones.size();i++) {
            var bone=bones.get(i);int o=i*14;
            if(!required.contains(i)){local.add(local(i,Vec3.ZERO,bone.restRotation()));continue;}
            for(int k=0;k<9;k++)if(!Float.isFinite(attributes[o+k]))throw new IllegalArgumentException("Non-finite YSM bone: "+bone.name());
            if(Math.abs(attributes[o+6]-1)>1e-5 || Math.abs(attributes[o+7]-1)>1e-5 || Math.abs(attributes[o+8]-1)>1e-5)
                throw new UnsupportedOperationException("MMD rigid skeleton cannot represent YSM scale channel: "+bone.name());
            // Cube visibility/color are separate material/geometry channels, not rigid skeleton transforms.
            local.add(local(i,new Vec3(-attributes[o+3],attributes[o+4],attributes[o+5]),
                new Vec3(attributes[o],attributes[o+1],attributes[o+2])));
        }
        return retargeter.apply(local);
    }
    public Map<Integer,Boolean> ikOverrides(){return ik;}
    public static SceneModelProfile calibrateArms(List<Bone> source,ScenePackageAssets target,SceneModelProfile profile) {
        if(!(target.model() instanceof ScenePackageAssets.Pmx || target.model() instanceof ScenePackageAssets.Pmd))
            throw new IllegalArgumentException("Arm reference calibration currently requires an MMD target");
        var positions=new LinkedHashMap<String,Vec3>();var global=new ArrayList<Pose>();
        for(int i=0;i<source.size();i++) {
            var b=source.get(i);if(b.parent() < -1 || b.parent()>=i)throw new IllegalArgumentException("YSM rig must be parent-first");
            var pose=local(source,i,Vec3.ZERO,b.restRotation());if(b.parent()>=0)pose=global.get(b.parent()).multiply(pose);
            global.add(pose);if(positions.put(b.name(),pose.position())!=null)throw new IllegalArgumentException("Ambiguous YSM bone: "+b.name());
        }
        return SkeletonReferencePose.arms(positions,SceneSkeleton.of(target),profile);
    }
    public Map<Integer,Pose> referencePose() {
        var rest=new ArrayList<Pose>();for(int i=0;i<bones.size();i++)rest.add(local(i,Vec3.ZERO,bones.get(i).restRotation()));
        return retargeter.apply(rest);
    }
    private Pose local(int index,Vec3 offset,Vec3 angles) {return local(bones,index,offset,angles);}
    private static Pose local(List<Bone> bones,int index,Vec3 offset,Vec3 angles) {
        var bone=bones.get(index);var parent=bone.parent()<0?Vec3.ZERO:bones.get(bone.parent()).pivot();
        var p=bone.pivot().subtract(parent).add(offset);
        var r=Rotation.axisAngle(new Vec3(0,0,1),angles.z())
            .multiply(Rotation.axisAngle(new Vec3(0,1,0),angles.y()))
            .multiply(Rotation.axisAngle(new Vec3(1,0,0),angles.x()));
        // Match the host's MMD source-to-world Z reflection; use YSM pixels until translationScale.
        return new Pose(new Vec3(p.x(),p.y(),-p.z()),Rotation.normalized(-r.x(),-r.y(),r.z(),r.w()));
    }
}
