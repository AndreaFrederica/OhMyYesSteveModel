package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;

public interface IGeoRenderer<T extends AnimatableEntity<?>> {
    default void preRender(GeoRenderData data, T animatable, Color color) {
        GlStateManager.scale(data.widthScale, data.heightScale, data.widthScale);
    }

    default void render(GeoRenderData data, T animatable, Color color) {
        GeoRenderType.commit(Tessellator.getInstance().getBuffer(), data, color);
    }

    default void postRender(GeoRenderData data, T animatable, Color color) {
        animatable.countRender();
    }
}
