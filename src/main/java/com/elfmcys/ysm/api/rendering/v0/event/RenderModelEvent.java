package com.elfmcys.ysm.api.rendering.v0.event;

import com.elfmcys.ysm.api.rendering.v0.TargetKind;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

@Cancelable
public class RenderModelEvent extends Event {
    private final Object target;
    private final TargetKind targetKind;
    private final GeoRenderData renderData;
    private final int color;

    public RenderModelEvent(Object target, TargetKind targetKind, GeoRenderData renderData, int color) {
        this.target = target;
        this.targetKind = targetKind;
        this.renderData = renderData;
        this.color = color;
    }

    /// 当前只有 Entity，以后说不定有别的
    public Object target() {
        return target;
    }

    public TargetKind targetKind() {
        return targetKind;
    }

    public GeoRenderData renderData() {
        return renderData;
    }

    public int color() {
        return color;
    }
}
