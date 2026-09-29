package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.config.ClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderArmEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ReplacePlayerHandRenderEvent {
    private static long events, replacements;
    private static String status = "no arm event";

    public static String diagnostics() {
        return "First-person: " + status + " (" + replacements + "/" + events + ")";
    }

    public static long replacementCount() { return replacements; }

    public static void resetDiagnostics() {
        events = replacements = 0;
        status = "no arm event";
    }
    /**
     * Forge invokes this for the vanilla first-person arm draw.  The player entity
     * render event is not enough in first person: vanilla does not submit the
     * player model there, it submits only this arm.  Keep vanilla rendering when
     * the asynchronous model is not ready yet.
     */
    @SubscribeEvent
    public static void onRenderHand(RenderArmEvent event) {
        events++;
        status = "disabled or unavailable";
        if (!YesSteveModel.isAvailable()
                || ClientConfig.DISABLE_SELF_MODEL.get()
                || ClientConfig.DISABLE_SELF_HANDS.get()
                || !(event.getPlayer() instanceof LocalPlayer player)) {
            return;
        }

        status = "capability unavailable";
        player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            if (!cap.isInitializedAndEnabled()
                    || cap.getModelRenderTarget() == null
                    || cap.getModelVariant() == null) {
                status = "model pending or disabled";
                return;
            }
            PoseStack poseStack = event.getPoseStack();
            MultiBufferSource multiBufferSource = event.getMultiBufferSource();
            var renderer = RegisterEntityRenderersEvent.getFirstPersonArmRenderer();
            boolean rendered = renderer.render(
                    player, cap, event.getArm(), poseStack, multiBufferSource,
                    event.getPackedLight(), Minecraft.getInstance().getPartialTick());
            status = renderer.status();
            if (rendered) {
                replacements++;
                event.setCanceled(true);
            }
        });
    }
}
