package com.elfmcys.ysm.client.sound.instance;

import com.elfmcys.ysm.client.sound.stream.AudioStreamProvider;
import com.elfmcys.ysm.client.sound.stream.CustomAudioStream;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.SoundEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class CustomSoundInstance extends MinecraftSoundInstance {
    private final AudioStreamProvider provider;
    @Nullable
    private volatile CustomAudioStream audioStream;

    public CustomSoundInstance(SoundEvent soundEvent, AudioStreamProvider provider, Entity entity) {
        super(soundEvent, entity);
        this.provider = provider;
        provider.stopped().thenRun(() -> Minecraft.getMinecraft().addScheduledTask(() -> {
            if (!super.isStopped()) {
                setStopped();
            }
        }));
    }

    /**
     * 供 {@code SoundManagerMixin} 投递给 paulscode 的 provider 形态：包装底层
     * {@link #provider}，在开流时顺带跟踪最新流实例，供 {@link #isStopped()} 判定。
     */
    public AudioStreamProvider streamProvider(boolean looping) {
        return new AudioStreamProvider() {
            @Override
            public @NotNull CompletableFuture<CustomAudioStream> openStream(boolean ignored) {
                // 循环语义由 paulscode（SoundManager 的 toLoop 实参）承担，见 YsmAudioCodec
                var future = provider.openStream(looping);
                future.thenAccept(stream -> audioStream = stream);
                return future;
            }

            @Override
            public void stop() {
                provider.stop();
            }

            @Override
            public CompletionStage<Void> stopped() {
                return provider.stopped();
            }
        };
    }

    @Override
    public boolean isStopped() {
        if (super.isStopped()) {
            return true;
        }
        // 循环音只会被显式停止；此时流会被反复 cleanup / 重新打开，不能据此判定结束
        if (this.canRepeat()) {
            return false;
        }
        var stream = this.audioStream;
        if (stream != null && stream.isClosed()) {
            this.setStopped();
            return true;
        }
        return false;
    }

    @Override
    public void setStopped() {
        provider.stop();
        super.setStopped();
    }
}
