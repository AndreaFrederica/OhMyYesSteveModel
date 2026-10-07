package com.elfmcys.ysm.mixin;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.event.CapabilityEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityThrowable.class)
public class EntityThrowableMixin {
    @Inject(method = "<init>(Lnet/minecraft/world/World;Lnet/minecraft/entity/EntityLivingBase;)V", at = @At("RETURN"))
    private void setOwner(World world, EntityLivingBase owner, CallbackInfo callbackInfo) {
        if (!YesSteveModel.isAvailable()) {
            return;
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
