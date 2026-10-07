package com.elfmcys.ysm.client.sound.instance;

import com.elfmcys.ysm.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.MovingSound;
import net.minecraft.entity.Entity;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;

public class MinecraftSoundInstance extends MovingSound implements ICanStopSound {
    protected final Entity entity;
    protected float configuredVolume = 1.0f;

    public MinecraftSoundInstance(SoundEvent soundEvent, Entity entity) {
        super(soundEvent, SoundCategory.PLAYERS);
        this.entity = entity;
        this.xPosF = (float) this.entity.posX;
        this.yPosF = (float) this.entity.posY;
        this.zPosF = (float) this.entity.posZ;
    }

    @Override
    public void update() {
        this.volume = this.configuredVolume * (float) ClientConfig.SOUND_VOLUME / 100.0f;
        if (this.entity.isDead) {
            this.donePlaying = true;
        } else {
            this.xPosF = (float) this.entity.posX;
            this.yPosF = (float) this.entity.posY;
            this.zPosF = (float) this.entity.posZ;
        }
    }

    public void setConfiguredVolume(float volume) {
        this.configuredVolume = volume;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public void setAsUI() {
        this.attenuationType = ISound.AttenuationType.NONE;
    }

    @Override
    public void setStopped() {
        this.donePlaying = true;
        Minecraft.getMinecraft().addScheduledTask(() -> {
            Minecraft.getMinecraft().getSoundHandler().stopSound(this);
        });
    }

    public void setLooping(boolean looping) {
        this.repeat = looping;
    }

    @Override
    public boolean isStopped() {
        return this.isDonePlaying();
    }
}
