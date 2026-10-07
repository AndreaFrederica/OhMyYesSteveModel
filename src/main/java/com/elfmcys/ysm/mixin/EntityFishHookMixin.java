package com.elfmcys.ysm.mixin;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.event.CapabilityEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityFishHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityFishHook.class)
public class EntityFishHookMixin {
    @Inject(method = "init", at = @At("RETURN"))
    private void setOwner(EntityPlayer owner, CallbackInfo ci) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        Entity entity = (Entity) (Object) this;
        // 仅在服务端执行
        if (entity.world == null || entity.world.isRemote) {
            return;
        }
        if (owner instanceof EntityPlayerMP serverPlayer) {
            CapabilityEvent.onProjectileSetOwner(entity, serverPlayer);
        }
        /*
        else if (TlmCommonCompat.isMaid(owner)) {
            TlmCommonCompat.onProjectileSetOwner(projectile, owner);
        }
        */
    }
}
