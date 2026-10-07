package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.sound.codec.YsmAudioCodec;
import com.elfmcys.ysm.client.sound.codec.YsmAudioUrl;
import net.minecraftforge.client.event.sound.SoundSetupEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import paulscode.sound.SoundSystemConfig;
import paulscode.sound.SoundSystemException;

// FIXME：音频系统仍几乎无法工作，待修复
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = YesSteveModel.MOD_ID)
public class AudioCodecSetupEvent {
    @SubscribeEvent
    public static void onSoundSetup(SoundSetupEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        try {
            SoundSystemConfig.setCodec(YsmAudioUrl.EXTENSION, YsmAudioCodec.class);
        } catch (SoundSystemException e) {
            YesSteveModel.LOGGER.error("Failed to register ysm audio codec", e);
        }
    }
}
