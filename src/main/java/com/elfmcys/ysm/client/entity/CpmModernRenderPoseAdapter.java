package com.elfmcys.ysm.client.entity;

import cc.sirrus.ysmlib.cpm.CpmPoseSession;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoBone;

/** Applies the Modern ModelBone/ScriptBone channels to a YSM bone. */
final class CpmModernRenderPoseAdapter {
    private CpmModernRenderPoseAdapter() {}
    static void apply(AnimatedGeoBone bone, CpmPoseSession.BonePose pose) {
        var p=pose.position(); var r=pose.rotation(); var s=pose.scale();
        // Java mode 2 produces [-attrX,+attrY,+attrZ]/16.  ScriptBone's
        // position is authored CPM pixels, so this is the exact encoding of
        // ModelBone.instantiate's (x,-y,z) local translation.
        bone.setPosition((float)-p[0], (float)-p[1], (float)p[2]);
        // The Modern pose session returns complete ModelRenderer rotation in
        // degrees: [source.y, source.x, source.z]. Java's rotateZYX emits
        // Rz(source.z) * Ry(source.x) * Rx(source.y), matching upstream
        // ModelBone.getQuaternion().
        bone.setRotation((float)Math.toRadians(r[0]),
                (float)Math.toRadians(r[1]),
                (float)Math.toRadians(r[2]));
        bone.setScale((float)s[0],(float)s[1],(float)s[2]); bone.setHidden(!pose.visible());
    }
}
