package com.elfmcys.ysm.mixin;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.event.CapabilityEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.20.1 在 {@code ServerPlayer#startRiding} 里的 {@code vehicle.positionRider(this)} 之后注入；
 * 1.12.2 的 {@code EntityPlayerMP#startRiding} 没有这一调用（{@code updatePassenger} 只在
 * {@code Entity.updateRidden} 的每帧路径上），因此改为在方法 RETURN 处按返回值判定成功后注入。
 * 差异：包发出时骑乘者尚未被移到载具偏移位，而 {@code CapabilityEvent.onVehicleSetModel} 只读
 * 载具实体 id 与 owner 的 capability，不读位置，故不可观测。
 */
// FIXME：是否会导致载具位偏移？
@Mixin(EntityPlayerMP.class)
public abstract class EntityPlayerMPMixin {
    @Inject(method = "startRiding(Lnet/minecraft/entity/Entity;Z)Z", at = @At("RETURN"))
    private void afterPositionRider(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> cir) {
        if (!YesSteveModel.isAvailable() || !cir.getReturnValueZ()) {
            return;
        }
        EntityPlayerMP serverPlayer = (EntityPlayerMP) (Object) this;
        if (vehicle.getControllingPassenger() == serverPlayer) {
            CapabilityEvent.onVehicleSetModel(vehicle, serverPlayer);
        }
    }
}
