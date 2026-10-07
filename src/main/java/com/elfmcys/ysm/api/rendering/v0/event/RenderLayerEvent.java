package com.elfmcys.ysm.api.rendering.v0.event;

import com.elfmcys.ysm.api.rendering.v0.TargetKind;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

@Cancelable
public class RenderLayerEvent extends Event {
    private final Object target;
    private final TargetKind targetKind;
    private final GeoRenderData renderData;

    public RenderLayerEvent(Object target, TargetKind targetKind, GeoRenderData renderData) {
        this.target = target;
        this.targetKind = targetKind;
        this.renderData = renderData;
    }

    public Object target() {
        return target;
    }

    public TargetKind targetKind() {
        return targetKind;
    }

    public GeoRenderData renderData() {
        return renderData;
    }
}
