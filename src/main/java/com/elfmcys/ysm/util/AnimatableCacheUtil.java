package com.elfmcys.ysm.util;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@SideOnly(Side.CLIENT)
public final class AnimatableCacheUtil {
    public static final Cache<ResourceLocation, Entity> ENTITIES_CACHE = CacheBuilder.newBuilder().expireAfterAccess(Duration.ofMinutes(5)).build();
}
