package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.SceneModelFeatures;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Body passes already contain the entity-to-camera transform; hand passes start at the camera. */
final class SceneViewPlacement {
    private SceneViewPlacement() {}

    static boolean worldFirstPerson(com.elfmcys.ysm.geckolib3.geo.RenderContext context) {
        return context.firstPersonMod() && !context.inventory() && !context.paperDoll()
                && !context.offScreen() && !context.irisShadow();
    }

    static Vector3f cameraOffset(SceneModelFeatures.FirstPerson config,float eyeHeight) {
        return new Vector3f((float)config.cameraX(),(float)config.cameraY()
                -(config.originBinding().isEmpty()?eyeHeight:0),(float)config.cameraZ());
    }

    static Matrix4f apply(Matrix4f placement, SceneModelFeatures.FirstPerson firstPerson,
                          Vector3f boneOrigin, boolean first, boolean cameraRelative) {
        if (!first) return new Matrix4f(placement);
        var origin = new Vector3f();
        if (cameraRelative && boneOrigin != null) placement.transformPosition(boneOrigin, origin);
        return new Matrix4f().translation((float) firstPerson.x() - origin.x,
                (float) firstPerson.y() - origin.y, (float) firstPerson.z() - origin.z).mul(placement);
    }

    /** Same orientation as the world view followed by the living renderer's body yaw. */
    static Matrix4f cameraBodyRotation(float pitch, float cameraYaw, float bodyYaw) {
        return new Matrix4f().rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(cameraYaw - bodyYaw));
    }
}
