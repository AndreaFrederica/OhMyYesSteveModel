package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;

public abstract class GeoLayerRenderer<T extends AnimatableEntity<?>> {
    protected static final float DEFAULT_SCALE = 1 / 16F;

    public GeoLayerRenderer() {
    }

    public abstract void render(T animatable, GeoRenderData renderData, double x, double y, double z);
}
