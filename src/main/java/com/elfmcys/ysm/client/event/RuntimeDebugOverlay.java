package com.elfmcys.ysm.client.event;

import cc.sirrus.ysmlib.YsmRuntime;
import com.elfmcys.ysm.YesSteveModel;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/** Minecraft owns presentation; the prerequisite owns provider diagnostics. */
@Mod.EventBusSubscriber(modid = YesSteveModel.MOD_ID, value = Dist.CLIENT)
public final class RuntimeDebugOverlay {
    private RuntimeDebugOverlay() {}

    private static final class Identity {
        private static final net.minecraftforge.forgespi.language.IModInfo INFO =
                ModList.get().getModContainerById(YsmRuntime.MOD_ID).orElseThrow().getModInfo();
        private static final String TITLE = INFO.getDisplayName() + " " + INFO.getVersion();
    }

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        // Forge posts DebugText even when the F3 overlay is hidden.
        if (!Minecraft.getInstance().options.renderDebug) {
            return;
        }
        var lines = event.getRight();
        lines.add("");
        lines.add(Identity.TITLE);
        lines.add(YsmRuntime.javaOnly() ? "Native acceleration: disabled"
                : "Native acceleration: preferred (see providers)");
        for (var module : YsmRuntime.diagnostics()) {
            lines.add(module.module() + ": " + module.implementation());
        }
        lines.add(ReplacePlayerHandRenderEvent.diagnostics());
        lines.add(RenderFirstPlayerBackground.diagnostics());
    }
}
