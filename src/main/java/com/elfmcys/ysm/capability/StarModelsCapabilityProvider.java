package com.elfmcys.ysm.capability;

import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class StarModelsCapabilityProvider implements ICapabilitySerializable<NBTTagList> {
    @CapabilityInject(StarModelsCapability.class)
    public static Capability<StarModelsCapability> STAR_MODELS_CAP = null;
    private final StarModelsCapability instance;

    public StarModelsCapabilityProvider() {
        this.instance = STAR_MODELS_CAP.getDefaultInstance();
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> cap, @Nullable EnumFacing side) {
        return cap == STAR_MODELS_CAP;
    }

    @Nullable
    @Override
    public <T> T getCapability(@NotNull Capability<T> cap, @Nullable EnumFacing side) {
        return hasCapability(cap, side) ? STAR_MODELS_CAP.cast(instance) : null;
    }

    @Override
    public void deserializeNBT(NBTTagList nbt) {
        instance.deserializeNBT(nbt);
    }

    @Override
    public NBTTagList serializeNBT() {
        return instance.serializeNBT();
    }
}
