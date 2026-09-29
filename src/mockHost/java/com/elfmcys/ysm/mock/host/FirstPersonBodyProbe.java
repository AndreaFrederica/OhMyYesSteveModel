package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.api.rendering.v0.TargetKind;
import com.elfmcys.ysm.api.rendering.v0.event.RenderModelEvent;
import com.elfmcys.ysm.client.compat.FirstPersonCompat;
import com.elfmcys.ysm.config.ClientConfig;
import dev.tr7zw.firstperson.api.FirstPersonAPI;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.util.Map;
import java.util.function.IntSupplier;

/** Observes real FirstPerson model passes; never invokes a renderer or forges its context. */
final class FirstPersonBodyProbe implements ClientProbe.PendingAction {
    private static FirstPersonBodyProbe active;
    private final Minecraft mc = Minecraft.getInstance();
    private final HostIo io;
    private final IntSupplier ticks;
    private final CameraType camera = mc.options.getCameraType();
    private final float pitch = mc.player.getXRot();
    private final float oldPitch = mc.player.xRotO;
    private final boolean debug = mc.options.renderDebug;
    private final boolean disabled = ClientConfig.DISABLE_SELF_MODEL.get();
    private final boolean enabled;
    private int phase, finishAt;
    private long bodyDraws, headDraws, bodyBefore;
    private final long nativeBefore = ClientProbe.nativeDraws();
    private String failure;

    FirstPersonBodyProbe(HostIo io, IntSupplier ticks) {
        if (!FirstPersonCompat.isInstalled()) throw new IllegalStateException("FirstPerson is not installed");
        this.io = io;
        this.ticks = ticks;
        enabled = FirstPersonAPI.isEnabled();
        FirstPersonAPI.setEnabled(true);
        ClientConfig.DISABLE_SELF_MODEL.set(false);
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.options.renderDebug = false;
        mc.player.setXRot(75);
        mc.player.xRotO = 75;
        mc.setScreen(null);
        active = this;
        finishAt = ticks.getAsInt() + 60;
    }

    static void observe(RenderModelEvent event) {
        if (active != null) active.onDraw(event);
    }

    private void onDraw(RenderModelEvent event) {
        if (event.target() != mc.player || event.targetKind() != TargetKind.PLAYER) return;
        var state = event.renderData().modelState;
        if (!state.isValid() || state.getVertexCount() == 0) return;
        var model = state.getModel();
        var baked = model.bakedModel().runtimeModel();
        boolean head = false, leftLeg = false, rightLeg = false, torso = false;
        for (int index : state.getNativeState().runtimeState().renderBones()) {
            for (int i = index; i >= 0; i = baked.bones().get(i).parent()) {
                String name = model.sortedBones().get(i).name();
                head |= name.equals("AllHead");
                leftLeg |= name.equals("LeftLeg");
                rightLeg |= name.equals("RightLeg");
                torso |= name.equals("UpBody");
            }
        }
        if (event.renderData().ctx.firstPersonMod()) {
            bodyDraws++;
            if (head || !leftLeg || !rightLeg || !torso) {
                failure = "Body visibility mismatch: head=" + head + " legs=" + leftLeg + "/" + rightLeg + " torso=" + torso;
            }
        } else if (mc.options.getCameraType() == CameraType.THIRD_PERSON_FRONT && head) {
            headDraws++;
        }
    }

    @Override public Map<String, ?> poll() throws Exception {
        try {
            if (mc.screen != null) {
                mc.setScreen(null);
                finishAt = ticks.getAsInt() + 60;
                return null;
            }
            // A server teleport may arrive just after this action was created.
            mc.player.setXRot(phase == 1 ? 0 : 75);
            mc.player.xRotO = mc.player.getXRot();
            if (failure != null) throw new IllegalStateException(failure);
            if (ticks.getAsInt() < finishAt) return null;
            if (phase == 0) {
                if (bodyDraws == 0) throw new IllegalStateException("FirstPerson did not submit a YSM body");
                screenshot("first-person-body.png");
                mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                mc.player.setXRot(0);
                mc.player.xRotO = 0;
            } else if (phase == 1) {
                if (headDraws == 0) throw new IllegalStateException("Third-person head was not restored");
                screenshot("third-person-head-restored.png");
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.player.setXRot(75);
                mc.player.xRotO = 75;
                ClientConfig.DISABLE_SELF_MODEL.set(true);
                bodyBefore = bodyDraws;
            } else {
                if (bodyDraws != bodyBefore) throw new IllegalStateException("Disabled YSM body was still submitted");
                screenshot("first-person-body-vanilla-fallback.png");
                restore();
                return Map.of("bodyDraws", bodyDraws, "headHidden", true, "legsVisible", true,
                        "thirdPersonHeadRestored", true, "disabledFallback", true,
                        "renderer", cc.sirrus.ysmlib.YsmRuntime.render().id(),
                        "nativeDraws", ClientProbe.nativeDraws() - nativeBefore,
                        "javaOnly", Boolean.getBoolean("ysm.runtime.javaOnly"));
            }
            phase++;
            finishAt = ticks.getAsInt() + 60;
            return null;
        } catch (Exception e) {
            restore();
            throw e;
        }
    }

    private void screenshot(String name) throws Exception {
        try (var screenshot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            screenshot.writeToFile(io.artifact(name));
        }
    }

    private void restore() {
        active = null;
        FirstPersonAPI.setEnabled(enabled);
        ClientConfig.DISABLE_SELF_MODEL.set(disabled);
        mc.options.setCameraType(camera);
        mc.options.renderDebug = debug;
        mc.player.setXRot(pitch);
        mc.player.xRotO = oldPitch;
    }
}
