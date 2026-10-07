package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.dispatch.SendResult;
import com.elfmcys.ysm.network.dispatch.TransportPort;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import net.minecraft.entity.player.EntityPlayerMP;

import java.util.Objects;

/** Netty-specific pressure and submission details kept behind TransportPort. */
public final class ForgeTransportPort implements TransportPort {
    private final EntityPlayerMP player;

    public ForgeTransportPort(EntityPlayerMP player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override
    public boolean isOpen() {
        return player.connection != null && player.connection.getNetworkManager().isChannelOpen();
    }

    @Override
    public boolean isWritable() {
        return isOpen() && player.connection.getNetworkManager().channel().isWritable();
    }

    @Override
    public long highWatermarkBytes() {
        return player.connection.getNetworkManager().channel().config().getWriteBufferHighWaterMark();
    }

    @Override
    public long pendingBytes() {
        var outbound = player.connection.getNetworkManager().channel().unsafe().outboundBuffer();
        return outbound == null ? 0 : outbound.totalPendingWriteBytes();
    }

    @Override
    public SendResult trySend(OutboundFrame frame) {
        if (!isOpen()) {
            return SendResult.FAILED;
        }
        try {
            NetworkHandler.sendFrame(player, frame);
            return SendResult.SUCCESS;
        } catch (RuntimeException failure) {
            return SendResult.FAILED;
        }
    }
}
