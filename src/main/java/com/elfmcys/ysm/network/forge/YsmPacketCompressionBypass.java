package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.network.NetworkHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.NettyCompressionEncoder;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;

/**
 * YSM 载荷已由 zstd 压缩过，再让 vanilla 的 deflate 压一遍纯属浪费 CPU。
 *
 * <p>与 1.20.1 的差异：1.12.2 没有 {@code ICustomPacket}，出站消息在这一段还是
 * {@link FMLProxyPacket}（channel 是 String）；且 FML 的 {@code fml:packet_handler}
 * 位于 {@code encoder} 之后，出站遍历时先经过它并就地拆成
 * {@code SPacketCustomPayload}/{@code CPacketCustomPayload}，
 * 故 Marker 必须挂在 {@code fml:packet_handler} 之后而不是 {@code encoder} 之后。
 */
public final class YsmPacketCompressionBypass {
    public static final String MARKER_HANDLER = "ysm_compression_marker";
    /** Marker 的挂点：FML 的入站/出站分流器，由 {@code NetworkDispatcher.insertIntoChannel} 装入。 */
    public static final String MARKER_ANCHOR = "fml:packet_handler";
    private static final ThreadLocal<Boolean> BYPASS_COMPRESSION = new ThreadLocal<>();

    private YsmPacketCompressionBypass() {
    }

    public static final class Marker extends ChannelOutboundHandlerAdapter {
        private final String channelName;

        public Marker() {
            this(NetworkHandler.CHANNEL_NAME);
        }

        Marker(String channelName) {
            this.channelName = channelName;
        }

        @Override
        public void write(ChannelHandlerContext context, Object message,
                          ChannelPromise promise) throws Exception {
            var bypass = message instanceof FMLProxyPacket packet
                    && channelName.equals(packet.channel());
            var previous = BYPASS_COMPRESSION.get();
            BYPASS_COMPRESSION.set(bypass);
            try {
                context.write(message, promise);
            } finally {
                if (previous == null) {
                    BYPASS_COMPRESSION.remove();
                } else {
                    BYPASS_COMPRESSION.set(previous);
                }
            }
        }
    }

    public static final class Encoder extends NettyCompressionEncoder {
        public Encoder(int threshold) {
            super(threshold);
        }

        @Override
        protected void encode(ChannelHandlerContext context, ByteBuf source, ByteBuf target)
                throws Exception {
            if (Boolean.TRUE.equals(BYPASS_COMPRESSION.get())) {
                var output = new PacketBuffer(target);
                output.writeVarInt(0);
                output.writeBytes(source);
                return;
            }
            super.encode(context, source, target);
        }
    }
}
