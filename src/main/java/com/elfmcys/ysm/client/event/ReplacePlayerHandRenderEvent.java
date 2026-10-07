package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.client.renderer.CustomFirstPersonArmRenderer;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.EnumHandSide;
import net.minecraftforge.client.event.RenderArmEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(value = Side.CLIENT)
public class ReplacePlayerHandRenderEvent {
//    TODO
//    @SubscribeEvent
//    public static void onRenderHand(RenderArmEvent event) {
//        if (!YesSteveModel.isAvailable()) {
//            return;
//        }
//        if (ClientConfig.DISABLE_SELF_MODEL) {
//            return;
//        }
//        if (ClientConfig.DISABLE_SELF_HANDS) {
//            return;
//        }
//        if (!(event.getPlayer() instanceof EntityPlayerSP player)) {
//            return;
//        }
//
//        var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
//        if (cap != null) {
//            if (!cap.isInitializedAndEnabled()) {
//                return;
//            }
//            EnumHandSide arm = event.getArm();
//            ModelRenderTarget model = cap.getModelRenderTarget();
//            var variant = cap.getModelVariant();
//            if (model == null || variant == null) {
//                return;
//            }
//            float partialTick = Minecraft.getMinecraft().getRenderPartialTicks();
//            CustomFirstPersonArmRenderer armRenderer = RegisterEntityRenderersEvent.getFirstPersonArmRenderer();
//            armRenderer.render(player, cap, arm, poseStack, multiBufferSource, event.getPackedLight(), partialTick);
//            event.setCanceled(true);
//        }
//    }
}
