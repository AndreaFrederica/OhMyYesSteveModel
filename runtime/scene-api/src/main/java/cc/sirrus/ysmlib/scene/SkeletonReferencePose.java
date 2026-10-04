package cc.sirrus.ysmlib.scene;

import java.util.*;

/** Non-destructive initial arm calibration. Segment directions do not determine twist; authors retain that control. */
public final class SkeletonReferencePose {
    private SkeletonReferencePose() {}

    public static SceneModelProfile arms(Map<String,Vec3> sourcePositions,List<SceneSkeleton.Bone> target,SceneModelProfile profile) {
        SceneSkeleton.validate(target,profile.bones());
        var result=new LinkedHashMap<>(profile.bones());
        for(String side:List.of("left","right")) {
            String upper=side+"UpperArm",lower=side+"LowerArm",hand=side+"Hand";
            Rotation forearm=null;
            for(var pair:List.of(List.of(upper,lower),List.of(lower,hand))) {
                var a=require(profile,pair.get(0));var b=require(profile,pair.get(1));
                String sa=profile.retarget().sourceBones().get(pair.get(0)),sb=profile.retarget().sourceBones().get(pair.get(1));
                if(!sourcePositions.containsKey(sa)||!sourcePositions.containsKey(sb))throw new IllegalArgumentException("Missing source arm segment: "+pair);
                var desired=sourcePositions.get(sb).subtract(sourcePositions.get(sa));
                var original=target.get(b.index()).position().subtract(target.get(a.index()).position());
                var rotation=align(original,SceneModelProfile.euler(a.pitch(),a.yaw(),a.roll()).rotate(desired));result.put(pair.get(0),reference(a,rotation));forearm=rotation;
            }
            result.put(hand,reference(require(profile,hand),forearm));
        }
        return profile.withBones(result);
    }
    private static SceneModelProfile.BoneBinding require(SceneModelProfile profile,String role) {
        var b=profile.bones().get(role);if(b==null||b.index()<0||b.weight()==0)throw new IllegalArgumentException("Bind arm segment before calibration: "+role);return b;
    }
    private static Rotation align(Vec3 a,Vec3 b) {
        double na=Math.sqrt(dot(a,a)),nb=Math.sqrt(dot(b,b));
        if(na<1e-7||nb<1e-7)throw new IllegalArgumentException("Zero-length reference arm segment");
        a=a.multiply((float)(1/na));b=b.multiply((float)(1/nb));double d=Math.max(-1,Math.min(1,dot(a,b)));
        if(d>1-1e-7)return Rotation.IDENTITY;
        if(d< -1+1e-7)return Rotation.axisAngle(cross(a,Math.abs(a.x())<.8?new Vec3(1,0,0):new Vec3(0,1,0)),Math.PI);
        var c=cross(a,b);return Rotation.normalized(c.x(),c.y(),c.z(),(float)(1+d));
    }
    private static double dot(Vec3 a,Vec3 b){return (double)a.x()*b.x()+(double)a.y()*b.y()+(double)a.z()*b.z();}
    private static Vec3 cross(Vec3 a,Vec3 b){return new Vec3(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
    private static SceneModelProfile.BoneBinding reference(SceneModelProfile.BoneBinding b,Rotation q) {
        double sin=2*((double)q.w()*q.y()-(double)q.z()*q.x());
        double x,y=Math.asin(Math.max(-1,Math.min(1,sin))),z;
        if(Math.abs(sin)>.999999){x=0;z=2*Math.atan2(q.z(),q.w());}
        else {x=Math.atan2(2*((double)q.w()*q.x()+(double)q.y()*q.z()),1-2*((double)q.x()*q.x()+(double)q.y()*q.y()));
            z=Math.atan2(2*((double)q.w()*q.z()+(double)q.x()*q.y()),1-2*((double)q.y()*q.y()+(double)q.z()*q.z()));}
        return b.withReference(Math.toDegrees(x),Math.toDegrees(y),Math.toDegrees(z));
    }
}
