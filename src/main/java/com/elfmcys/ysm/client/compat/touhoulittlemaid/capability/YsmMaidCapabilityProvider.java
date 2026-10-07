package com.elfmcys.ysm.client.compat.touhoulittlemaid.capability;

/*
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.CustomYsmMaidEntity;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SideOnly(Side.CLIENT)
public class YsmMaidCapabilityProvider implements ICapabilityProvider {
    @CapabilityInject(CustomYsmMaidEntity.class)
    public static Capability<CustomYsmMaidEntity> CAP = null;

    private CustomYsmMaidEntity instance;
    private final EntityMaid maid;

    public YsmMaidCapabilityProvider(EntityMaid maid) {
        this.maid = maid;
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> cap, @Nullable EnumFacing side) {
        return cap == CAP;
    }

    @Nullable
    @Override
    public <T> T getCapability(@NotNull Capability<T> cap, @Nullable EnumFacing side) {
        if (cap != CAP) {
            return null;
        }
        if (instance == null) {
            instance = new CustomYsmMaidEntity(this.maid, true);
        }
        return CAP.cast(instance);
    }
}
*/
