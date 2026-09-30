package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.gui.ModelLoadingScreen;
import com.elfmcys.ysm.client.gui.overlay.LoadingStateScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = YesSteveModel.MOD_ID, value = Dist.CLIENT)
public final class ModelLoadingOverlayEvent {
    @SubscribeEvent
    public static void afterScreen(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ModelLoadingScreen)) {
            LoadingStateScreen.renderProgress(event.getGuiGraphics(),
                    event.getScreen().width, event.getScreen().height);
        }
    }
}
