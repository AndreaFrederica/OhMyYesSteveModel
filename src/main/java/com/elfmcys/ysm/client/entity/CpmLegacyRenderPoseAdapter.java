package com.elfmcys.ysm.client.entity;

import cc.sirrus.ysmlib.cpm.CpmPoseSession;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoBone;

/** Applies the Legacy CustomModel channels to a YSM bone. */
final class CpmLegacyRenderPoseAdapter {
    private CpmLegacyRenderPoseAdapter() {}
    static void apply(AnimatedGeoBone bone, CpmPoseSession.BonePose pose) {
        var p=pose.position(); var r=pose.rotation(); var s=pose.scale();
        // CustomModel.Bone.update() first evaluates position and applies
        // scl(-1,-1,1). JavaModelState's Legacy local branch then consumes
        // T(-attrX,+attrY,+attrZ), so encode the source renderer translation
        // [-x,-y,+z] as attributes [x,-y,+z].
        bone.setPosition((float)p[0], (float)-p[1], (float)p[2]);
        // JavaModelState consumes rotateZYX(z, y, x), while Legacy
        // CustomModel's quaternion is Rz(source.z) * Ry(source.x) *
        // Rx(source.y). Swap source X/Y at this boundary; Modern uses a
        // different channel mapping and must stay on its own adapter.
        bone.setRotation((float)Math.toRadians(r[1]), (float)Math.toRadians(r[0]), (float)Math.toRadians(r[2]));
        bone.setScale((float)s[0],(float)s[1],(float)s[2]); bone.setHidden(!pose.visible());
    }
}
