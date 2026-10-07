package com.elfmcys.ysm.mixin;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.accessor.IArrowExtraInfo;
import com.elfmcys.ysm.event.CapabilityEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.commons.lang3.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SuppressWarnings("AddedMixinMembersNamePattern")
@Mixin(EntityArrow.class)
public class EntityArrowMixin implements IArrowExtraInfo {
    @Unique
    private String shootItemId = StringUtils.EMPTY;
    @SuppressWarnings("ShadowModifiers") // FMC 的 at 干的
    @Shadow
    protected boolean inGround;
    @Shadow
    protected int timeInGround;

    @Unique
    @Override
    public boolean isInGround() {
        return inGround;
    }

    @Unique
    @Override
    public int inGroundTime() {
        return timeInGround;
    }

    @Unique
    @Override
    public String getShootItemId() {
        return shootItemId;
    }

    @Inject(method = "<init>(Lnet/minecraft/world/World;Lnet/minecraft/entity/EntityLivingBase;)V", at = @At("RETURN"))
    private void setOwner(World world, EntityLivingBase owner, CallbackInfo callbackInfo) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        // 设置 owner 时，缓存一下射击时主手物品 ID，用于 molang
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(owner.getHeldItemMainhand().getItem());
        if (key != null) {
            shootItemId = key.toString();
        }
        // 仅在服务端执行
        if (world == null || world.isRemote) {
            return;
        }
        if (owner instanceof EntityPlayerMP serverPlayer) {
            CapabilityEvent.onProjectileSetOwner((Entity) (Object) this, serverPlayer);
        }
        /*
        else if (TlmCommonCompat.isMaid(owner)) {
            TlmCommonCompat.onProjectileSetOwner(projectile, owner);
        }
        */
    }
}
