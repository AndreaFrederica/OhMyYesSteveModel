package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.client.animation.molang.roaming.RemoteRoamingStruct;
import com.elfmcys.ysm.client.entity.CustomProjectileEntity;
import com.elfmcys.ysm.model.domain.Hash256;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IProjectile;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

@SideOnly(Side.CLIENT)
public class ProjectileAnimatableCapability extends CustomProjectileEntity {
    private @Nullable RemoteRoamingStruct roamingStruct;

    public ProjectileAnimatableCapability(Entity projectile) {
        super(projectile);
    }

    public void init(Hash256 ownerModelHash) {
        updateModelHash(ownerModelHash);
        setInitialized();
    }

    public void initRoamingVars(Int2FloatOpenHashMap vars) {
        if (vars != null) {
            // 无条件同步服务端数据
            roamingStruct = new RemoteRoamingStruct(vars);
        } else {
            roamingStruct = null;
        }
    }

    @Override
    protected void preAnimationSetup(float seekTime, boolean shouldTick) {
        super.preAnimationSetup(seekTime, shouldTick);
        getAnimationProcessor().putRemoteStruct(roamingStruct);
    }
}
