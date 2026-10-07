package com.elfmcys.ysm.config;

import com.elfmcys.ysm.YesSteveModel;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = YesSteveModel.MOD_ID, value = Side.CLIENT)
public class ConfigSyncEvent {
    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.getModID().equals(YesSteveModel.MOD_ID)) {
            Config.readFromProp();
        }
    }
}
