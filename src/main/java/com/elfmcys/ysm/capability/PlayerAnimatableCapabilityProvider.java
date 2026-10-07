package com.elfmcys.ysm.capability;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SideOnly(Side.CLIENT)
public class PlayerAnimatableCapabilityProvider implements ICapabilityProvider {
    @CapabilityInject(PlayerAnimatableCapability.class)
    public static Capability<PlayerAnimatableCapability> CAP = null;
    private PlayerAnimatableCapability instance;
    private AbstractClientPlayer player;

    public PlayerAnimatableCapabilityProvider(AbstractClientPlayer player) {
        this.player = player;
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> cap, @Nullable EnumFacing side) {
        return cap == CAP;
    }

    @Nullable
    @Override
    public <T> T getCapability(@NotNull Capability<T> cap, @Nullable EnumFacing side) {
        if (!hasCapability(cap, side)) return null;
        if (instance == null) {
            this.instance = new PlayerAnimatableCapability(player);
            player = null;
        }
        return CAP.cast(instance);
    }
}
