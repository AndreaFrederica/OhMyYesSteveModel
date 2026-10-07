package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.sound.codec.YsmAudioUrl;
import com.elfmcys.ysm.client.sound.instance.CustomSoundInstance;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import paulscode.sound.SoundSystem;

import java.net.URL;

@Mixin(SoundManager.class)
public class SoundManagerMixin {
    @WrapOperation(
            method = "playSound",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/audio/SoundManager$SoundSystemStarterThread;newStreamingSource(ZLjava/lang/String;Ljava/net/URL;Ljava/lang/String;ZFFFIF)V",
                    remap = false
            )
    )
    private void onNewStreamingSource(@Coerce SoundSystem soundSystem, boolean priority, String sourceName, URL url,
                                        String identifier, boolean toLoop, float x, float y, float z,
                                        int attenuationModel, float distOrRoll,
                                        Operation<Void> original, ISound sound) {
        if (sound instanceof CustomSoundInstance customSound) {
            try {
                url = YsmAudioUrl.create(customSound.streamProvider(false));
                identifier = YsmAudioUrl.identifier();
            } catch (Exception error) {
                // 原版逻辑为静音
                YesSteveModel.LOGGER.error("Failed to build the custom source url", error);
            }
        }
        original.call(soundSystem, priority, sourceName, url, identifier, toLoop, x, y, z, attenuationModel, distOrRoll);
    }

    @WrapOperation(
            method = "playSound",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/audio/SoundManager$SoundSystemStarterThread;newSource(ZLjava/lang/String;Ljava/net/URL;Ljava/lang/String;ZFFFIF)V",
                    remap = false
            )
    )
    private void onNewSource(@Coerce SoundSystem soundSystem, boolean priority, String sourceName, URL url,
                               String identifier, boolean toLoop, float x, float y, float z,
                               int attenuationModel, float distOrRoll,
                               Operation<Void> original, ISound sound) {
        if (sound instanceof CustomSoundInstance customSound) {
            try {
                url = YsmAudioUrl.create(customSound.streamProvider(toLoop));
                identifier = YsmAudioUrl.identifier();
            } catch (Exception error) {
                // 原版逻辑为静音
                YesSteveModel.LOGGER.error("Failed to build the custom source url", error);
            }
        }
        original.call(soundSystem, priority, sourceName, url, identifier, toLoop, x, y, z, attenuationModel, distOrRoll);
    }
}
