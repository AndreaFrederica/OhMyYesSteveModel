package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.gui.ExtraPlayerConfigScreen;
import com.elfmcys.ysm.config.ExtraPlayerScreenConfig;
import com.elfmcys.ysm.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(value = Side.CLIENT, modid = YesSteveModel.MOD_ID)
public class RenderExtraPlayerScreenEvent {
    @SubscribeEvent
    public static void render(RenderGameOverlayEvent.Text event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }

        if (ExtraPlayerScreenConfig.DISABLE_PLAYER_RENDER) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.player;
        if (player == null) {
            return;
        }
        if (mc.currentScreen instanceof ExtraPlayerConfigScreen) {
            return;
        }

        double posX = ExtraPlayerScreenConfig.PLAYER_POS_X;
        double posY = ExtraPlayerScreenConfig.PLAYER_POS_Y;
        float scale = ExtraPlayerScreenConfig.PLAYER_SCALE;
        float yawOffset = ExtraPlayerScreenConfig.PLAYER_YAW_OFFSET;

        RenderUtil.renderExtraPlayerEntity(player, posX, posY, scale, yawOffset, -500, event.getPartialTicks());
    }
}
