package com.elfmcys.ysm.capability;

import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SideOnly(Side.CLIENT)
public class ProjectileAnimatableCapabilityProvider implements ICapabilityProvider {
    @CapabilityInject(ProjectileAnimatableCapability.class)
    public static Capability<ProjectileAnimatableCapability> CAP = null;
    private ProjectileAnimatableCapability instance;
    private Entity projectile;

    public ProjectileAnimatableCapabilityProvider(Entity projectile) {
        this.projectile = projectile;
    }

    public ProjectileAnimatableCapability initialize() {
        if (this.instance == null) {
            this.instance = new ProjectileAnimatableCapability(projectile);
            this.projectile = null;
        }
        return this.instance;
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> cap, @Nullable EnumFacing side) {
        return cap == CAP && instance != null;
    }

    @Nullable
    @Override
    public <T> T getCapability(@NotNull Capability<T> cap, @Nullable EnumFacing side) {
        return hasCapability(cap, side) ? CAP.cast(instance) : null;
    }
}
