package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.compat.FirstPersonCompat;
/*
import com.elfmcys.ysm.client.compat.realcamera.RealCameraCompat;
*/
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.util.PersonView;
import com.elfmcys.ysm.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(value = Side.CLIENT)
public class ReplacePlayerRenderEvent {
    // 取消拔刀剑的刀层
    // FIXME：也许可以使用 SlashBlade 原生的配置 bool？
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRender(RenderPlayerEvent.Pre event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        EntityPlayer playerRender = event.getEntityPlayer();
        EntityPlayerSP playerSelf = Minecraft.getMinecraft().player;
        if (playerRender.equals(playerSelf) && ClientConfig.DISABLE_SELF_MODEL) {
            return;
        }
        if (!playerRender.equals(playerSelf) && ClientConfig.DISABLE_OTHER_MODEL) {
            return;
        }
        if (event.getEntityPlayer().isSpectator()) {
            return;
        }
        var cap = playerRender.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
        if (cap != null) {
            var ctx = RenderUtil.extractRenderContext();
            if (cap.isInitializedAndEnabled()) {
                if (!PersonView.isFirstPersonView(cap)
                        || FirstPersonCompat.isRenderingPlayer()
                        /*
                        || RealCameraCompat.isActive()
                        */
                        || ClientConfig.DISABLE_EXTERNAL_FIRST_PERSON_ANIM) {
                    event.setCanceled(true);
                    RegisterEntityRenderersEvent.getPlayerRenderer().doRender(
                            playerRender, event.getX(), event.getY(), event.getZ(),
                            playerRender.rotationYaw, event.getPartialRenderTick());
                }
            }
        }
    }
}
