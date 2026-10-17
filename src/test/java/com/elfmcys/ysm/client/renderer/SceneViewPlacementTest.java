package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.SceneModelFeatures;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneViewPlacementTest {
    private static final SceneModelFeatures.FirstPerson HEAD =
            new SceneModelFeatures.FirstPerson(List.of(), "head", 0, 0, 0);

    @Test void cameraOnlyOffsetsCanBeTunedWithoutMovingTheWorldBody() {
        var config=new SceneModelFeatures.FirstPerson(List.of(),"",.1,.2,.3,List.of(),.5f,.04,.12,.25);
        assertTrue(SceneViewPlacement.cameraOffset(config,1.62f).equals(new Vector3f(.04f,-1.5f,.25f),1e-6f));
        assertTrue(SceneViewPlacement.apply(new Matrix4f(),config,null,true,false)
                .equals(new Matrix4f().translation(.1f,.2f,.3f),1e-6f));
        var bone=new SceneModelFeatures.FirstPerson(List.of(),"head",0,0,0,List.of(),.5f,0,.12,.25);
        assertEquals(new Vector3f(0,.12f,.25f),SceneViewPlacement.cameraOffset(bone,1.62f));
    }

    @Test void guiAndShadowPassesNeverInheritFirstPersonHeadFiltering() {
        assertTrue(SceneViewPlacement.worldFirstPerson(new com.elfmcys.ysm.geckolib3.geo.RenderContext(false,false,true,false,false,false,false)));
        assertFalse(SceneViewPlacement.worldFirstPerson(new com.elfmcys.ysm.geckolib3.geo.RenderContext(false,false,true,true,false,false,false)));
        assertFalse(SceneViewPlacement.worldFirstPerson(new com.elfmcys.ysm.geckolib3.geo.RenderContext(false,false,true,false,true,false,false)));
        assertFalse(SceneViewPlacement.worldFirstPerson(new com.elfmcys.ysm.geckolib3.geo.RenderContext(false,false,true,false,false,true,false)));
        assertFalse(SceneViewPlacement.worldFirstPerson(new com.elfmcys.ysm.geckolib3.geo.RenderContext(false,true,true,false,false,false,false)));
    }

    @Test void bodyPassDoesNotSubtractHeadHeightFromAnAlreadyPositionedEntity() {
        var placement = new Matrix4f().translation(.2f, .1f, -.3f).rotateY(.7f).scale(1.25f);
        var head = new Vector3f(.1f, 1.7f, .05f);
        var world = new Matrix4f().rotateX((float)Math.PI / 2).translate(0, -1.62f, 0);
        var actual = new Matrix4f(world).mul(SceneViewPlacement.apply(placement, HEAD, head, true, false));
        assertTrue(actual.equals(new Matrix4f(world).mul(placement), 1e-6f),
                "FirstPerson's world/camera placement must be applied exactly once");
    }

    @Test void cameraPassCentersThePlacedBoneIncludingModelScaleAndOffset() {
        var placement = new Matrix4f().translation(.2f, .3f, -.4f).rotateY(.8f).scale(1.4f);
        var head = new Vector3f(.1f, 1.8f, -.1f);
        var offset = new SceneModelFeatures.FirstPerson(List.of(), "head", .1, -.2, -.3);
        var actual = SceneViewPlacement.apply(placement, offset, head, true, true);
        assertTrue(actual.transformPosition(new Vector3f(head)).equals(new Vector3f(.1f, -.2f, -.3f), 1e-6f));
        assertTrue(placement.equals(new Matrix4f().translation(.2f, .3f, -.4f).rotateY(.8f).scale(1.4f), 1e-6f),
                "View construction must not mutate the shared placement");
    }

    @Test void viewSpecificOffsetsDoNotChangeThirdPersonAndRemainAdditiveInTheBodyPass() {
        var placement = new Matrix4f().scale(1.2f);
        var offset = new SceneModelFeatures.FirstPerson(List.of(), "head", .1, -.2, -.3);
        assertEquals(placement, SceneViewPlacement.apply(placement, offset, new Vector3f(0, 2, 0), false, true));
        assertTrue(new Matrix4f().translation(.1f, -.2f, -.3f).mul(placement)
                .equals(SceneViewPlacement.apply(placement, offset, new Vector3f(0, 2, 0), true, false), 1e-6f));
    }

    @Test void cameraFallbackMatchesWorldCameraAndBodyYawAtAllLookDirections() {
        for (float pitch : new float[]{-80, 0, 75, 90}) for (float yaw : new float[]{-170, 0, 135}) {
            float bodyYaw = 45;
            var world = new Matrix4f().rotateX((float)Math.toRadians(pitch))
                    .rotateY((float)Math.toRadians(yaw + 180)).rotateY((float)Math.toRadians(180 - bodyYaw))
                    .rotateY((float)Math.PI);
            var fallback = SceneViewPlacement.cameraBodyRotation(pitch, yaw, bodyYaw).rotateY((float)Math.PI);
            assertTrue(world.equals(fallback, 1e-6f), "Camera-relative body must follow pitch and relative yaw");
            var feet = fallback.transformPosition(new Vector3f(0, -1.7f, 0));
            if (pitch == 90) assertTrue(feet.z < -1.69f, "Looking down must put feet in front of the camera");
        }
    }
}
