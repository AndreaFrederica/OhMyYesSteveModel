package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.client.event.ClientLoggedEvent;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.SPacketJoinGame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetHandlerPlayClient.class)
public class NetHandlerPlayClientMixin {
    @Inject(method = "handleJoinGame(Lnet/minecraft/network/play/server/SPacketJoinGame;)V", at = @At("RETURN"))
    private void ysm$localPlayerJoined(SPacketJoinGame packet, CallbackInfo callback) {
        ClientLoggedEvent.onPlayerLoggedIn();
    }
}
