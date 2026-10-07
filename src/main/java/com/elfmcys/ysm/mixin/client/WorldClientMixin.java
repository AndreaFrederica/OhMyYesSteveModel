package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.event.EntityLoadEvent;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.12.2 Forge 没有 {@code EntityLeaveLevelEvent}。客户端实体移除的规范入口是
 * {@code WorldClient#removeEntityFromWorld(int)}，由 {@code NetHandlerPlayClient.handleDestroyEntities}
 * 与 {@code WorldClient.addEntityToWorld} 的替换路径调用。
 *
 * <p>与 1.20.1 的差异：区块卸载导致的实体移除不走此方法，故 {@code CLIENT_REVISIONS} 可能残留条目；
 * 实体重新进入视野时服务端发的 FULL 携带更大的 revision，不会被 stale 判定拦掉，无功能后果。
 */
@Mixin(WorldClient.class)
public class WorldClientMixin {
    @Inject(method = "removeEntityFromWorld(I)Lnet/minecraft/entity/Entity;", at = @At("HEAD"))
    private void ysm$onEntityRemoved(int entityId, CallbackInfoReturnable<Entity> callback) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        EntityLoadEvent.onEntityLeaveWorld(entityId);
    }
}
