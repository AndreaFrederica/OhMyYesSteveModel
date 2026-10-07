package com.elfmcys.ysm.mixin;

import com.elfmcys.ysm.network.forge.YsmPacketCompressionBypass;
import io.netty.channel.Channel;
import net.minecraft.network.NettyCompressionEncoder;
import net.minecraft.network.NetworkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.20.1 的 {@code Connection#setupCompression(int, boolean)} 在 1.12.2 是单参 {@code setCompressionThreshold(int)}。 */
@Mixin(NetworkManager.class)
public abstract class NetworkManagerMixin {
    @Shadow
    private Channel channel;

    @Inject(method = "setCompressionThreshold(I)V", at = @At("RETURN"))
    private void ysm$configureCompressionBypass(int threshold, CallbackInfo callback) {
        var pipeline = channel.pipeline();
        if (threshold < 0) {
            if (pipeline.get(YsmPacketCompressionBypass.MARKER_HANDLER) != null) {
                pipeline.remove(YsmPacketCompressionBypass.MARKER_HANDLER);
            }
            return;
        }
        var current = pipeline.get("compress");
        if (current instanceof NettyCompressionEncoder
                && !(current instanceof YsmPacketCompressionBypass.Encoder)) {
            pipeline.replace("compress", "compress",
                    new YsmPacketCompressionBypass.Encoder(threshold));
        }
        if (pipeline.get(YsmPacketCompressionBypass.MARKER_ANCHOR) != null
                && pipeline.get(YsmPacketCompressionBypass.MARKER_HANDLER) == null) {
            pipeline.addAfter(YsmPacketCompressionBypass.MARKER_ANCHOR,
                    YsmPacketCompressionBypass.MARKER_HANDLER,
                    new YsmPacketCompressionBypass.Marker());
        }
    }
}
