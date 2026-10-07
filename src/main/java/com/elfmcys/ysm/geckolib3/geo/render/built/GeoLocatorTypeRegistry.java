package com.elfmcys.ysm.geckolib3.geo.render.built;

import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class GeoLocatorTypeRegistry {
    private static final HashMap<ResourceLocation, GeoLocatorType> MAP = new HashMap<>();
    private static final AtomicBoolean FROZEN = new AtomicBoolean(false);

    static void register(GeoLocatorType type) {
        if (FROZEN.getAcquire()) {
            throw new IllegalStateException("GeoLocatorTypeRegistry is already frozen");
        }
        synchronized (MAP) {
            if (MAP.computeIfAbsent(type.id(), id -> type) != type) {
                throw new IllegalStateException("GeoLocatorType " + type.id() + " is already registered");
            }
        }
    }

    public void freeze() {
        FROZEN.setRelease(true);
    }

    @Nullable
    public static GeoLocatorType get(ResourceLocation id) {
        return MAP.get(id);
    }
}
