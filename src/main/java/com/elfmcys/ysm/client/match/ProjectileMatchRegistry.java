package com.elfmcys.ysm.client.match;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityDragonFireball;

import java.util.HashSet;
import java.util.Set;

public final class ProjectileMatchRegistry {
    private static final Set<Class<? extends Entity>> PROJECTILES = new HashSet<>();

    static {
        PROJECTILES.add(EntityArrow.class);
        PROJECTILES.add(EntityDragonFireball.class);
    }
}
