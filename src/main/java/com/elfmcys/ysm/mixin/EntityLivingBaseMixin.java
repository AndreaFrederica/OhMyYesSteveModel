package com.elfmcys.ysm.mixin;

import com.elfmcys.ysm.event.LivingShieldBlockEvent;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityLivingBase.class)
public class EntityLivingBaseMixin {
    @Inject(
            method = "attackEntityFrom",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/EntityLivingBase;damageShield(F)V"
            )
    )
    private void onShieldBlock(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingShieldBlockEvent.onShieldBlock((EntityLivingBase) (Object) this);
    }
}
