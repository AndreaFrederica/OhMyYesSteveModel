package com.elfmcys.ysm.client.sound.instance;

import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.elfmcys.ysm.init.ModSounds;
import com.elfmcys.ysm.util.IdUtil;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.util.SoundEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class SoundInstanceManager {
    private final Int2ReferenceOpenHashMap<ICanStopSound> instanceMap;
    private final ReferenceArrayList<ICanStopSound> unnamedInstanceList;

    public SoundInstanceManager() {
        this.instanceMap = new Int2ReferenceOpenHashMap<>(0);
        this.unnamedInstanceList = new ReferenceArrayList<>(0);
    }

    public boolean playSound(AnimatableEntity<?> animatable, int id, String soundName, boolean force, @Nullable Consumer<@Nullable MinecraftSoundInstance> configure) {
        MinecraftSoundInstance instance;
        if (soundName.contains(":")) {
            // 如果声音名带冒号，那么大概率就是调用原版音频，因为 Windows 中冒号不是合法的文件名
            var soundId = IdUtil.tryParse(soundName);
            if (soundId != null) {
                SoundEvent soundEvent = new SoundEvent(soundId);
                instance = new MinecraftSoundInstance(soundEvent, animatable.getEntity());
            } else {
                instance = null;
            }
        } else {
            // 否则认为是自定义的音频文件
            instance = animatable.getSoundStream(soundName)
                    .map(provider -> new CustomSoundInstance(ModSounds.CUSTOM, provider, animatable.getEntity()))
                    .orElse(null);
        }
        if (configure != null) {
            configure.accept(instance);
        }
        if (instance == null) {
            return false;
        }

        if (id != 0) {
            if (force) {
                var oldInstance = instanceMap.put(id, instance);
                if (oldInstance != null && !oldInstance.isStopped()) {
                    oldInstance.setStopped();
                }
            } else {
                var newInstance = instanceMap.compute(id, (ignored, oldInstance) -> {
                    if (oldInstance == null || oldInstance.isStopped()) {
                        return instance;
                    }
                    return oldInstance;
                });
                if (newInstance != instance) {
                    instance.setStopped();
                    return false;
                }
            }
        } else {
            unnamedInstanceList.add(instance);
        }
        Minecraft.getMinecraft().addScheduledTask(() -> {
            var sounds = Minecraft.getMinecraft().getSoundHandler();
            sounds.playSound(instance);
            if (!sounds.isSoundPlaying(instance)) {
                instance.setStopped();
            }
        });
        return true;
    }

    public boolean stopPlayingSound(int id) {
        if (id == 0) {
            return false;
        }
        var instance = instanceMap.remove(id);
        if (instance != null) {
            instance.setStopped();
            return true;
        }
        return false;
    }

    public void stopAllPlayingSounds() {
        instanceMap.values().forEach(ICanStopSound::setStopped);
        for (var instance: unnamedInstanceList) {
            instance.setStopped();
        }
        instanceMap.clear();
        unnamedInstanceList.clear();
    }

    public void trim() {
        var iter = instanceMap.int2ReferenceEntrySet().fastIterator();
        while (iter.hasNext()) {
            var instance = iter.next();
            if (instance.getValue().isStopped()) {
                iter.remove();
            }
        }

        unnamedInstanceList.removeIf(ICanStopSound::isStopped);
    }
}
