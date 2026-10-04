package cc.sirrus.ysmlib.scene;

import java.util.Optional;

/** Evaluated source bones to right-handed Y-up metre-space item attachments. Contains no game rendering. */
public final class SceneHandSockets {
    private SceneHandSockets() {}
    public static Optional<Matrix4> resolve(ScenePackagePlayback.Frame frame,SceneModelProfile profile,boolean left) {
        var held=profile.heldItems();var socket=left?held.left():held.right();
        var binding=profile.bones().get(socket.binding());
        if(!held.enabled()||!socket.enabled()||binding==null||binding.index()<0)return Optional.empty();
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
