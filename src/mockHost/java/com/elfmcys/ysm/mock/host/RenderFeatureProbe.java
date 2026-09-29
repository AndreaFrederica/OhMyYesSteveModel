package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.rendering.v0.TargetKind;
import com.elfmcys.ysm.api.rendering.v0.event.RegisterRenderStateModifierEvent;
import com.elfmcys.ysm.api.rendering.v0.event.RenderModelEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import java.util.concurrent.atomic.AtomicLongArray;

@Mod.EventBusSubscriber(modid = MockHostMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RenderFeatureProbe {
    private static final AtomicLongArray calls = new AtomicLongArray(TargetKind.values().length);
    private static final AtomicLongArray draws = new AtomicLongArray(TargetKind.values().length);
    public static long calls(TargetKind kind) { return calls.get(kind.ordinal()); }
    public static long draws(TargetKind kind) { return draws.get(kind.ordinal()); }

    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> YesSteveModel.registerEventHandler(RenderFeatureProbe.class));
    }

    @SubscribeEvent public static void register(RegisterRenderStateModifierEvent event) {
        for (var kind : TargetKind.values()) {
            event.addModifier(kind, (target, data) -> calls.incrementAndGet(kind.ordinal()));
        }
    }

    @SubscribeEvent public static void onDraw(RenderModelEvent event) {
        if (event.renderData().modelState.getVertexCount() > 0) draws.incrementAndGet(event.targetKind().ordinal());
        FirstPersonBodyProbe.observe(event);
    }
}
