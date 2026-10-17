package com.elfmcys.ysm.mock.host;

import cc.sirrus.ysmlib.scene.ScenePackagePlayback;
import com.elfmcys.ysm.api.rendering.v0.TargetKind;
import com.elfmcys.ysm.api.rendering.v0.event.RenderSceneEvent;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import java.util.Map;
import java.util.function.IntSupplier;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Real Forge pass observation for a configured avatar; never invokes the scene renderer itself. */
final class SceneFirstPersonProbe implements ClientProbe.PendingAction {
    private static SceneFirstPersonProbe active;
    private final Minecraft mc=Minecraft.getInstance();
    private final HostIo io;
    private final IntSupplier ticks;
    private final CameraType camera=mc.options.getCameraType();
    private final float pitch=mc.player.getXRot();
    private final boolean enabled=FirstPersonAPI.isEnabled();
    private int phase,finishAt;
    private long bodyDraws,cameraDraws,thirdDraws,lastBodySequence=Long.MIN_VALUE;
    private float maxHeadDistance;
    private long controllerFrames,heldItemFrames;
    private final boolean forward=mc.options.keyUp.isDown();
    private String failure;

    SceneFirstPersonProbe(HostIo io,IntSupplier ticks) {
        this.io=io;this.ticks=ticks;active=this;
        FirstPersonAPI.setEnabled(true);mc.options.setCameraType(CameraType.FIRST_PERSON);mc.setScreen(null);
        // Selection acknowledgement precedes asynchronous geometry/texture readiness.
        // Start the observation interval on the first actual scene body, not the ack.
        finishAt=ticks.getAsInt()+2000;
    }

    static void observe(RenderSceneEvent event) {if(active!=null)active.onDraw(event);}
    private void onDraw(RenderSceneEvent event) {
        if(event.target()!=mc.player)return;
        var context=event.context();
        if(context.inventory()||context.paperDoll()||context.offScreen()||context.irisShadow())return;
        if(!event.frame().drivenBones().isEmpty())controllerFrames++;
        if(!event.firstPerson()){if(phase==3)thirdDraws++;return;}
        long sequence=com.elfmcys.ysm.client.event.ClientTickEvent.renderSequence();
        if(event.targetKind()==TargetKind.PLAYER) {
            if(phase==0&&bodyDraws==0)finishAt=ticks.getAsInt()+60;
            if(lastBodySequence==sequence)failure="Duplicate FirstPerson scene body in a render sequence";
            lastBodySequence=sequence;bodyDraws++;
        } else if(event.targetKind()==TargetKind.PLAYER_BACKGROUND) {
            if(lastBodySequence==sequence)failure="Camera background redrew a submitted FirstPerson body";
            cameraDraws++;
        }
        var cap=mc.player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElseThrow(()->new IllegalStateException("Missing player capability"));
        var binding=cap.getGeneralMeshInstance().profile().bones().get("head");
        var details=event.frame().details();cc.sirrus.ysmlib.scene.Vec3 head;
        if(details instanceof ScenePackagePlayback.Mmd frame)head=frame.value().pose().bones().get(binding.index()).position();
        else if(details instanceof ScenePackagePlayback.Vrm frame)head=cc.sirrus.ysmlib.scene.SceneRig.position(frame.value().pose().globalMatrices().get(binding.index()));
        else throw new IllegalArgumentException("First-person avatar probe requires MMD or VRM");
        float distance=new Matrix4f().set(event.view().modelView().copy())
                .transformPosition(new Vector3f(head.x(),head.y(),head.z())).length();
        maxHeadDistance=Math.max(maxHeadDistance,distance);
        if(!Float.isFinite(distance)||distance>.9f)failure="Head displaced from camera: "+distance+" metres";
    }

    @Override public Map<String,?> poll() throws Exception {
        try {
            mc.setScreen(null);mc.player.setXRot(phase==1||phase>=3?0:75);mc.player.xRotO=mc.player.getXRot();
            mc.options.keyUp.setDown(phase==3&&ticks.getAsInt()<finishAt-35);
            if(phase==3){var cap=mc.player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElseThrow(()->new IllegalStateException("Missing player capability"));if(cap.getGeneralMeshInstance().renderedHeldItems()>0)heldItemFrames++;}
            if(failure!=null)throw new IllegalStateException(failure);
            if(ticks.getAsInt()<finishAt)return null;
            String screenshot=switch(phase){case 0->"scene-body-look-down.png";case 1->"scene-body-look-forward.png";case 2->"scene-camera-fallback-look-down.png";case 3->"scene-third-person-restored.png";default->"scene-third-person-empty-hands.png";};
            try(var pixels=Screenshot.takeScreenshot(mc.getMainRenderTarget())){pixels.writeToFile(io.artifact(screenshot));}
            if(phase==0&&bodyDraws==0)throw new IllegalStateException("FirstPerson body was not drawn");
            if(phase==2&&cameraDraws==0)throw new IllegalStateException("Camera fallback was not drawn after disabling FirstPerson");
            if(phase>=3)writePose(screenshot);
            if(phase==4){
                if(thirdDraws==0)throw new IllegalStateException("Third-person model did not return");
                if(controllerFrames==0)throw new IllegalStateException("No YSM controller pose reached avatar");
                if(heldItemFrames==0)throw new IllegalStateException("Held items were not rendered on avatar sockets");
                var result=Map.of("controllerFrames",controllerFrames,"heldItemFrames",heldItemFrames,"noDuplicateBody",true,"headOriginNearCamera",true,"maxHeadDistance",maxHeadDistance,
                        "bodyDraws",bodyDraws,"cameraFallbackDraws",cameraDraws,"thirdPersonDraws",thirdDraws);
                restore();return result;
            }
            phase++;if(phase==1)mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);finishAt=ticks.getAsInt()+50;
            if(phase==2)FirstPersonAPI.setEnabled(false);
            if(phase==3)mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            if(phase==4){mc.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,net.minecraft.world.item.ItemStack.EMPTY);mc.player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,net.minecraft.world.item.ItemStack.EMPTY);}
            return null;
        } catch(Exception|Error e){restore();throw e;}
    }
    private void writePose(String screenshot)throws Exception {
        var instance=mc.player.getCapability(PlayerAnimatableCapabilityProvider.CAP).orElseThrow(()->new IllegalStateException("Missing player capability")).getGeneralMeshInstance();
        if(instance.frame().details() instanceof ScenePackagePlayback.Vrm vrm){
            var positions=new java.util.LinkedHashMap<String,Object>();
            for(String role:java.util.List.of("hips","chest","head","leftUpperArm","leftLowerArm","leftHand","rightUpperArm","rightLowerArm","rightHand")){
                var b=instance.profile().bones().get(role);if(b!=null)positions.put(role,cc.sirrus.ysmlib.scene.SceneRig.position(vrm.value().pose().globalMatrices().get(b.index())));
            }
            java.nio.file.Files.writeString(io.artifact(screenshot+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Map.of("positions",positions,"profile",instance.profile())));
        }
    }
    private void restore(){active=null;mc.options.keyUp.setDown(forward);FirstPersonAPI.setEnabled(enabled);mc.options.setCameraType(camera);mc.player.setXRot(pitch);mc.player.xRotO=pitch;}
}
