package com.elfmcys.ysm.api.rendering.v0.event;

import com.elfmcys.ysm.api.rendering.v0.TargetKind;
import com.elfmcys.ysm.api.rendering.v0.type.RenderStateModifier;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import it.unimi.dsi.fastutil.objects.ReferenceLists;
import net.minecraftforge.fml.common.eventhandler.Event;
import org.apache.commons.lang3.tuple.ImmutablePair;

public class RegisterRenderStateModifierEvent extends Event {
    private final ReferenceArrayList<ImmutablePair<TargetKind, RenderStateModifier<?, ?>>> modifiers = new ReferenceArrayList<>();

    public RegisterRenderStateModifierEvent() {
    }

    public <E, R extends GeoRenderData> void addModifier(TargetKind kind, RenderStateModifier<E, R> modifier) {
        modifiers.add(ImmutablePair.of(kind, modifier));
    }

    public ReferenceList<ImmutablePair<TargetKind, RenderStateModifier<?, ?>>> get() {
        return ReferenceLists.unmodifiable(modifiers);
    }
}
