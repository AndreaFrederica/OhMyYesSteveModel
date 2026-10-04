package com.elfmcys.ysm.mixin.client;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Borrow the actual uploaded lightmap without changing Minecraft's shader bindings. */
@Mixin(LightTexture.class)
public interface LightTextureAccessor {
    @Accessor("lightTexture")
    DynamicTexture ysm$lightTexture();
}
