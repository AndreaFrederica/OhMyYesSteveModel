package com.elfmcys.ysm.capability;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ModelInfoCapabilityProvider implements ICapabilitySerializable<NBTTagCompound> {
    @CapabilityInject(ModelInfoCapability.class)
    public static Capability<ModelInfoCapability> MODEL_INFO_CAP = null;
    private final ModelInfoCapability instance;

    public ModelInfoCapabilityProvider() {
        this.instance = MODEL_INFO_CAP.getDefaultInstance();
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> cap, @Nullable EnumFacing side) {
        return cap == MODEL_INFO_CAP;
    }

    @Nullable
    @Override
    public <T> T getCapability(@NotNull Capability<T> cap, @Nullable EnumFacing side) {
        return hasCapability(cap, side) ? MODEL_INFO_CAP.cast(instance) : null;
    }

    @Override
    public void deserializeNBT(NBTTagCompound nbt) {
        instance.deserializeNBT(nbt);
    }

    @Override
    public NBTTagCompound serializeNBT() {
        return instance.serializeNBT();
    }
}
