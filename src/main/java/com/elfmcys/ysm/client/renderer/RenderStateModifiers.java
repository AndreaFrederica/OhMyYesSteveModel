package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.rendering.v0.TargetKind;
import com.elfmcys.ysm.api.rendering.v0.event.RegisterRenderStateModifierEvent;
import com.elfmcys.ysm.api.rendering.v0.type.RenderStateModifier;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Immutable registration snapshot published after extension discovery and before gameplay. */
public final class RenderStateModifiers {
    private static volatile Map<TargetKind, List<RenderStateModifier<?, ?>>> modifiers = Map.of();
    private RenderStateModifiers() {}

    public static void init() {
        var event = new RegisterRenderStateModifierEvent();
        YesSteveModel.postEvent(event);
        var snapshot = new EnumMap<TargetKind, List<RenderStateModifier<?, ?>>>(TargetKind.class);
        for (var entry : event.get()) {
            snapshot.computeIfAbsent(entry.getLeft(), key -> new ArrayList<>()).add(entry.getRight());
        }
        snapshot.replaceAll((kind, values) -> List.copyOf(values));
        modifiers = Map.copyOf(snapshot);
    }

    @SuppressWarnings("unchecked")
    public static void apply(TargetKind kind, Object target, GeoRenderData data) {
        if (kind == null) return;
        for (var modifier : modifiers.getOrDefault(kind, List.of())) {
            ((RenderStateModifier<Object, GeoRenderData>) modifier).apply(target, data);
        }
    }
}
