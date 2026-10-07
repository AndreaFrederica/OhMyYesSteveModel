package com.elfmcys.ysm.capability;

import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AuthModelsCapabilityProvider implements ICapabilitySerializable<NBTTagList> {
    @CapabilityInject(AuthModelsCapability.class)
    public static Capability<AuthModelsCapability> AUTH_MODELS_CAP = null;
    private final AuthModelsCapability instance;

    public AuthModelsCapabilityProvider() {
        this.instance = AUTH_MODELS_CAP.getDefaultInstance();
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> cap, @Nullable EnumFacing side) {
        return cap == AUTH_MODELS_CAP;
    }

    @Nullable
    @Override
    public <T> T getCapability(@NotNull Capability<T> cap, @Nullable EnumFacing side) {
        return hasCapability(cap, side) ? AUTH_MODELS_CAP.cast(instance) : null;
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
