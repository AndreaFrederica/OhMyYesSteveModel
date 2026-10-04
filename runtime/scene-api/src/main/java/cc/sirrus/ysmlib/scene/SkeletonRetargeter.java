package cc.sirrus.ysmlib.scene;

import java.util.*;

/**
 * Rigid skeleton retargeting after source animation evaluation. Both rigs must use the same handedness/up axis;
 * translationScale converts source units to target units. This does not parse YSM curves or emulate Molang queries.
 * Scale, visibility and expression channels must be handled explicitly by the host, never silently fed as poses.
 */
public final class SkeletonRetargeter {
    public record Bone(String name,int parent,Pose restLocal) {public Bone{Objects.requireNonNull(name);Objects.requireNonNull(restLocal);}}
    private final List<Bone> source,target;private final Pose[] sourceRest,targetRest;
    private final Map<Integer,Bound> bindings;private final double translationScale;
    private record Bound(int source,SceneModelProfile.BoneBinding binding){}
    public SkeletonRetargeter(List<Bone> source,List<Bone> target,SceneModelProfile profile){
        this.source=List.copyOf(source);this.target=List.copyOf(target);translationScale=profile.retarget().translationScale();
        sourceRest=global(this.source,this.source.stream().map(Bone::restLocal).toList());targetRest=global(this.target,this.target.stream().map(Bone::restLocal).toList());
        var names=new HashMap<String,Integer>();for(int i=0;i<source.size();i++)if(names.put(source.get(i).name(),i)!=null)throw new IllegalArgumentException("Ambiguous source bone: "+source.get(i).name());
        var map=new HashMap<Integer,Bound>();
        for(var entry:profile.retarget().sourceBones().entrySet()){
            Integer index=names.get(entry.getValue());if(index==null)throw new IllegalArgumentException("Missing YSM source bone: "+entry.getValue());
            var b=profile.bones().get(entry.getKey());if(b==null||b.index()<0)throw new IllegalArgumentException("Unbound target role: "+entry.getKey());
            if(b.index()>=target.size()||!target.get(b.index()).name().equals(b.name()))throw new IllegalArgumentException("Stale target bone: "+b.name());
            var path=new ArrayDeque<String>();int parent=target.get(b.index()).parent();while(parent>=0){path.addFirst(parent+":"+target.get(parent).name());parent=target.get(parent).parent();}
            if(!String.join("/",path).equals(b.parent()))throw new IllegalArgumentException("Stale target hierarchy: "+b.name());
            if(b.weight()>0)map.put(b.index(),new Bound(index,b));
        }
        bindings=Map.copyOf(map);
    }
    /** Target local DELTAS relative to target rest, suitable for MMD rotation/translation channels. */
    public Map<Integer,Pose> apply(List<Pose> sourceLocal){
        Pose[] animated=global(source,sourceLocal),output=new Pose[target.size()];
        var rotationOnly=new ArrayList<Pose>(source.size());
        for(int i=0;i<source.size();i++)rotationOnly.add(new Pose(source.get(i).restLocal().position(),sourceLocal.get(i).rotation()));
        var withoutTranslations=global(source,rotationOnly);var sourceOffsets=new Vec3[source.size()];
        for(int i=0;i<source.size();i++)sourceOffsets[i]=animated[i].position().subtract(withoutTranslations[i].position());
        var appliedOffsets=new Vec3[target.size()];var result=new LinkedHashMap<Integer,Pose>();byte[] visit=new byte[target.size()];
        for(int i=0;i<target.size();i++)target(i,animated,sourceOffsets,output,appliedOffsets,result,visit);return Map.copyOf(result);
    }
    private void target(int i,Pose[] animated,Vec3[] sourceOffsets,Pose[] out,Vec3[] offsets,Map<Integer,Pose> deltas,byte[] visited){
        if(visited[i]==2)return;if(visited[i]==1)throw new IllegalArgumentException("Cyclic target skeleton");visited[i]=1;
        var bone=target.get(i);Pose parent=Pose.IDENTITY;Vec3 inherited=Vec3.ZERO;
        if(bone.parent()>=0){target(bone.parent(),animated,sourceOffsets,out,offsets,deltas,visited);parent=out[bone.parent()];inherited=offsets[bone.parent()];}
        offsets[i]=inherited;
        var binding=bindings.get(i);Pose local=bone.restLocal();
        if(binding!=null){var b=binding.binding();var rest=sourceRest[binding.source()];var current=animated[binding.source()];
            var correction=Rotation.axisAngle(new Vec3(0,0,1),Math.toRadians(b.roll())).multiply(Rotation.axisAngle(new Vec3(0,1,0),Math.toRadians(b.yaw()))).multiply(Rotation.axisAngle(new Vec3(1,0,0),Math.toRadians(b.pitch())));
            var delta=correction.multiply(current.rotation().multiply(rest.rotation().inverse())).multiply(correction.inverse());
            var global=Rotation.slerp(parent.rotation().multiply(local.rotation()),delta.multiply(b.referenceRotation()).multiply(targetRest[i].rotation()),b.weight());
            Vec3 position=local.position();
            // Transfer authored animation translations, including unbound control ancestors,
            // without copying source rest limb lengths or applying parent motion twice.
            var desired=correction.rotate(sourceOffsets[binding.source()]).multiply((float)translationScale);
            var extra=desired.subtract(inherited).multiply((float)b.weight());
            position=position.add(parent.rotation().inverse().rotate(extra));offsets[i]=inherited.add(extra);
            local=new Pose(position,parent.rotation().inverse().multiply(global));
            deltas.put(i,new Pose(position.subtract(bone.restLocal().position()),bone.restLocal().rotation().inverse().multiply(local.rotation())));
        }
        out[i]=parent.multiply(local);visited[i]=2;
    }
    private static Pose[] global(List<Bone> rig,List<Pose> local){
        if(local.size()!=rig.size())throw new IllegalArgumentException("Pose bone count differs from rig");Pose[] out=new Pose[rig.size()];byte[] visited=new byte[rig.size()];
        for(int i=0;i<rig.size();i++)globalBone(i,rig,local,out,visited);return out;
    }
    private static void globalBone(int i,List<Bone> rig,List<Pose> local,Pose[] out,byte[] visited){
        if(visited[i]==2)return;if(visited[i]==1)throw new IllegalArgumentException("Cyclic source skeleton");visited[i]=1;
        int p=rig.get(i).parent();if(p< -1||p>=rig.size())throw new IllegalArgumentException("Invalid parent index");
        if(p>=0){globalBone(p,rig,local,out,visited);out[i]=out[p].multiply(local.get(i));}else out[i]=local.get(i);visited[i]=2;
    }
}
