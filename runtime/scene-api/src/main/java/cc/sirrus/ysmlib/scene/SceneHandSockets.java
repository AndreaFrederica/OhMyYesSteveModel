package cc.sirrus.ysmlib.scene;

import java.util.Optional;

/** Evaluated source bones to right-handed Y-up metre-space item attachments. Contains no game rendering. */
public final class SceneHandSockets {
    private SceneHandSockets() {}
    public static Optional<Matrix4> resolve(ScenePackagePlayback.Frame frame,SceneModelProfile profile,boolean left) {
        return resolve(frame,profile,left?profile.heldItems().left():profile.heldItems().right());
    }
    public static Optional<Matrix4> resolve(ScenePackagePlayback.Frame frame,SceneModelProfile profile,SceneModelProfile.HandSocket socket) {
        return resolve(frame,profile,socket,true,Rotation.IDENTITY);
    }
    /** Node formats may have nonidentity rest axes, unlike the MMD skeleton. */
    public static Optional<Matrix4> resolve(ScenePackagePlayback.Frame frame,SceneModelProfile profile,SceneModelProfile.HandSocket socket,Rotation restWorld) {
        return resolve(frame,profile,socket,true,restWorld);
    }
    /** Compile scale-independent world rest axes once for attachment evaluation. */
    public static java.util.List<Rotation> restAxes(SceneAsset scene) {
        var rig=new SceneRig(scene);var rotations=new Rotation[scene.nodes().size()];var queue=new java.util.ArrayDeque<Integer>();
        for(int i=0;i<rotations.length;i++)if(rig.parent(i)<0)queue.add(i);
        while(!queue.isEmpty()){
            int i=queue.remove(),parent=rig.parent(i);rotations[i]=parent<0?rig.restRotations().get(i):rotations[parent].multiply(rig.restRotations().get(i));
            var children=scene.nodes().get(i).children();for(int c=0;c<children.size();c++)queue.add(children.get(c));
        }
        return java.util.List.of(rotations);
    }
    /** A metre-space bone origin/orientation, independent of held-item presentation switches. */
    public static Optional<Matrix4> resolveBone(ScenePackagePlayback.Frame frame,SceneModelProfile profile,String binding) {
        return resolve(frame,profile,new SceneModelProfile.HandSocket(true,binding,0,0,0,0,0,0,1),false,Rotation.IDENTITY);
    }
    /** A source-unit bone-local point transformed to host metres, without item reference correction. */
    public static Optional<Vec3> resolveBonePoint(ScenePackagePlayback.Frame frame,SceneModelProfile profile,String binding,Vec3 local) {
        var point=coordinates(frame.thirdPerson().coordinates()).transformDirection(local);
        return resolveBone(frame,profile,binding).map(matrix->matrix.transformPoint(point));
    }
    private static Optional<Matrix4> resolve(ScenePackagePlayback.Frame frame,SceneModelProfile profile,SceneModelProfile.HandSocket socket,boolean item,Rotation restWorld) {
        var held=profile.heldItems();
        var binding=profile.bones().get(socket.binding());
        if(item&&!held.enabled()||!socket.enabled()||binding==null||binding.index()<0)return Optional.empty();
        int i=binding.index();Matrix4 bone;
        var detail=frame.details();
        if(detail instanceof ScenePackagePlayback.Mmd mmd)bone=mmd.value().pose().bones().get(i).matrix();
        else if(detail instanceof ScenePackagePlayback.Fbx fbx) {
            var m=fbx.value().nodes().get(i).world();
            bone=new Matrix4((float)m.get(0),(float)m.get(1),(float)m.get(2),0,(float)m.get(3),(float)m.get(4),(float)m.get(5),0,
                (float)m.get(6),(float)m.get(7),(float)m.get(8),0,(float)m.get(9),(float)m.get(10),(float)m.get(11),1);
        } else {
            var pose=detail instanceof ScenePackagePlayback.Scene scene?scene.pose():detail instanceof ScenePackagePlayback.Vrm vrm?vrm.value().pose():null;
            if(pose==null)throw new IllegalArgumentException("Scene has no evaluated skeleton for held items");
            bone=pose.globalMatrices().get(i);
        }
        var c=coordinates(frame.thirdPerson().coordinates());
        var offset=new Transform(new Vec3((float)socket.x(),(float)socket.y(),(float)socket.z()),
            SceneModelProfile.euler(socket.pitch(),socket.yaw(),socket.roll()),Vec3.ONE.multiply((float)socket.scale())).matrix();
        // Conjugation converts the bone's orientation and handedness. Its origin is
        // taken from the ordinary source-to-metre transform separately: conjugating
        // a scale matrix would otherwise cancel metersPerUnit for the bone position.
        var oriented=c.multiply(bone).multiply(c.inverse());
        var origin=c.multiply(bone).transformPoint(Vec3.ZERO);
        float referenceWeight=frame.referenceWeights().getOrDefault(binding.index(),0f);
        if(item&&profile.features().socketSpace()==SceneModelFeatures.SocketSpace.RETARGET_REFERENCE && referenceWeight>0) {
            var reference=new Transform(Vec3.ZERO,Rotation.slerp(Rotation.IDENTITY,binding.referenceRotation().multiply(restWorld).inverse(),referenceWeight),Vec3.ONE).matrix();
            oriented=oriented.multiply(c.multiply(reference).multiply(c.inverse()));
        }
        return Optional.of(withOrigin(oriented,origin).multiply(offset));
    }
    private static Matrix4 withOrigin(Matrix4 value,Vec3 origin) {
        float[] m=value.copy();m[12]=origin.x();m[13]=origin.y();m[14]=origin.z();m[15]=1;
        return new Matrix4(m);
    }
    public static Matrix4 coordinates(SceneAsset.Coordinates c) {
        float s=(float)c.metersPerUnit();
        var scale=new Transform(Vec3.ZERO,Rotation.IDENTITY,new Vec3(s,s,c.rightHanded()?s:-s)).matrix();
        var rotation=switch(c.upAxis()) {
            case "Y" -> Rotation.IDENTITY;
            case "Z" -> Rotation.axisAngle(new Vec3(1,0,0),-Math.PI/2);
            case "X" -> Rotation.axisAngle(new Vec3(0,0,1),Math.PI/2);
            default -> throw new IllegalArgumentException("Unsupported socket up axis: "+c.upAxis());
        };
        return scale.multiply(new Transform(Vec3.ZERO,rotation,Vec3.ONE).matrix());
    }
}
