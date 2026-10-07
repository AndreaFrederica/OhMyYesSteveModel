package com.elfmcys.ysm.event.api;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;
import org.jetbrains.annotations.Nullable;

@Cancelable
public class SpecialPlayerRenderEvent extends Event {
    private final EntityPlayer player;
    private final CustomPlayerEntity customPlayer;
    private final String modelId;
    @Nullable
    private ResourceLocation textureLocationOverride;

    // 没这个方法 forge 会报错
    public SpecialPlayerRenderEvent() {
        player = null;
        customPlayer = null;
        modelId = null;
    }

    public SpecialPlayerRenderEvent(EntityPlayer player, CustomPlayerEntity customPlayer, String modelId) {
        this.player = player;
        this.customPlayer = customPlayer;
        this.modelId = modelId;
    }

    public EntityPlayer getPlayer() {
        return player;
    }

    public CustomPlayerEntity getCustomPlayer() {
        return customPlayer;
    }

    public String getModelId() {
        return modelId;
    }

    @Nullable
    public ResourceLocation getTextureLocationOverride() {
        return textureLocationOverride;
    }

    public void setTextureLocationOverride(@Nullable ResourceLocation textureLocationOverride) {
        this.textureLocationOverride = textureLocationOverride;
    }
}
