package com.elfmcys.ysm.capability;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class VehicleModelInfoCapabilityProvider implements ICapabilitySerializable<NBTTagCompound> {
    @CapabilityInject(VehicleModelInfoCapability.class)
    public static Capability<VehicleModelInfoCapability> CAP = null;
    private final VehicleModelInfoCapability instance;

    public VehicleModelInfoCapabilityProvider() {
        this.instance = CAP.getDefaultInstance();
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> cap, @Nullable EnumFacing side) {
        return cap == CAP;
    }

    @Nullable
    @Override
    public <T> T getCapability(@NotNull Capability<T> cap, @Nullable EnumFacing side) {
        return hasCapability(cap, side) ? CAP.cast(instance) : null;
    }

    @Override
    public NBTTagCompound serializeNBT() {
        return instance.serializeNBT();
    }

    @Override
    public void deserializeNBT(NBTTagCompound nbt) {
        instance.deserializeNBT(nbt);
    }
}
