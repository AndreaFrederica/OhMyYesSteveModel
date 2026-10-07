package com.elfmcys.ysm.capability;

import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ClientLazyCapabilityProvider implements ICapabilityProvider {
    @CapabilityInject(ClientLazyCapability.class)
    public static Capability<ClientLazyCapability> CAP = null;
    private final ClientLazyCapability instance;

    public ClientLazyCapabilityProvider(
            VehicleAnimatableCapabilityProvider vehicleAnimatableCapabilityProvider,
            @Nullable ProjectileAnimatableCapabilityProvider projectileAnimatableCapabilityProvider) {
        this.instance = new ClientLazyCapability(vehicleAnimatableCapabilityProvider, projectileAnimatableCapabilityProvider);
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
}
