package com.elfmcys.ysm.network.forge;

import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.function.Supplier;

public final class InboundContext {
    private InboundContext() {
    }

    /** 对照 {@code FMLNetworkEvent.ServerCustomPacketEvent}：接收侧为服务端。 */
    public static Supplier<NetworkEvent.Context> server(FMLNetworkEvent.ServerCustomPacketEvent event) {
        var context = new NetworkEvent.Context(event.getManager(), Side.SERVER);
        return () -> context;
    }

    /** 对照 {@code FMLNetworkEvent.ClientCustomPacketEvent}：接收侧为客户端。 */
    public static Supplier<NetworkEvent.Context> client(FMLNetworkEvent.ClientCustomPacketEvent event) {
        var context = new NetworkEvent.Context(event.getManager(), Side.CLIENT);
        return () -> context;
    }
}
