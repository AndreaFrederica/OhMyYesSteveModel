package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import net.minecraft.client.entity.EntityPlayerSP;

public class LocalPlayerRespawnEvent {
    public static void onCloned(EntityPlayerSP oldPlayer, EntityPlayerSP newPlayer) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        // 仅客户端运行时，执行后续的 copyFrom 会出现 null 值问题
        if (!NetworkHandler.isRemoteChannelPresent()) {
            return;
        }

        var oldCap = oldPlayer.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
        var newCap = newPlayer.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
        if (oldCap != null && newCap != null) {
            newCap.moveFrom(oldCap);
        }
        ClientProtocolGateway.localPlayerCloned();
    }
}
