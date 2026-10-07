package com.elfmcys.ysm.network;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import com.elfmcys.ysm.network.forge.ForgeUniBufferIO;
import com.elfmcys.ysm.network.forge.InboundContext;
import com.elfmcys.ysm.network.forge.NetworkEvent;
import com.elfmcys.ysm.network.frame.FrameCodec;
import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.MessageDirection;
import com.elfmcys.ysm.network.protocol.ProtocolMessageSpec;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.network.protocol.ProtocolVersion;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;
import us.hebi.quickbuf.ProtoMessage;

import java.util.function.Supplier;

// FIXME：当前为 Agent 迁移，需重构
/** The sole Forge adapter for the current YSM frame protocol. */
public final class NetworkHandler {
    public static final String VERSION = ProtocolVersion.TRANSPORT_VERSION;
    /** FML 通道名受 vanilla {@code readString(20)} 约束，"ysm:protocol" 为 12 字符。 */
    public static final String CHANNEL_NAME = YesSteveModel.MOD_ID + ":" + ProtocolVersion.CHANNEL_PATH;
    private static final Inbound INBOUND = new Inbound();
    private static @Nullable FMLEventChannel channel;

    private NetworkHandler() {
    }

    public static synchronized void init() {
        if (channel == null) {
            var created = NetworkRegistry.INSTANCE.newEventDrivenChannel(CHANNEL_NAME);
            created.register(INBOUND);
            channel = created;
        }
    }

    public static boolean isPlayerChannelPresent(EntityPlayerMP player) {
        return player.connection != null && isChannelPresent(player.connection.getNetworkManager());
    }

    @SideOnly(Side.CLIENT)
    public static boolean isRemoteChannelPresent() {
        NetHandlerPlayClient listener = Minecraft.getMinecraft().getConnection();
        return listener != null && isChannelPresent(listener.getNetworkManager());
    }

    /**
     * 1.12.2 的 FML 没有可查询的远端通道注册表（对照 1.20.1 {@code EventNetworkChannel#isRemotePresent}
     * 走 {@code NetworkHooks.getConnectionData}），YsmProtocol 的通道存在性由握手消息自行协商。
     */
    public static boolean isChannelPresent(@Nullable NetworkManager connection) {
        return connection != null && connection.channel() != null;
    }

    public static void sendToServer(ProtoMessage<?> message) {
        sendToServer(payload(message));
    }

    public static void sendToServer(NetworkPayload<?> payload) {
        if (!isRemoteChannelPresent()) {
            payload.close();
            return;
        }
        try (payload; var protobuf = ProtocolBuffer.serialize(payload.protobuf());
             var attachment = payload.raw().map(UniBuffer::borrow)
                     .orElseGet(() -> ArrayBuffer.allocate(0))) {
            var spec = messageSpec(payload.protobuf(), MessageDirection.CLIENT_TO_SERVER);
            spec.attachmentPolicy().validate(attachment.size());
            try (var frame = FrameCodec.encode(spec.id(), protobuf, attachment)) {
                synchronized (CHANNEL_NAME) {
                    require().sendToServer(packet(frame));
                }
            }
        }
    }

    public static void sendToClientPlayer(ProtoMessage<?> message, EntityPlayer player) {
        sendToClientPlayer(payload(message), player);
    }

    public static void sendToClientPlayer(NetworkPayload<?> payload, EntityPlayer player) {
        send(payload, (EntityPlayerMP) player);
    }

    public static void broadcastToAllPlayers(ProtoMessage<?> message) {
        try (var payload = payload(message);
             var protobuf = ProtocolBuffer.serialize(payload.protobuf());
             var attachment = payload.raw().map(UniBuffer::borrow)
                     .orElseGet(() -> ArrayBuffer.allocate(0))) {
            var spec = messageSpec(payload.protobuf(), MessageDirection.SERVER_TO_CLIENT);
            spec.attachmentPolicy().validate(attachment.size());
            try (var frame = FrameCodec.encode(spec.id(), protobuf, attachment)) {
                synchronized (CHANNEL_NAME) {
                    require().sendToAll(packet(frame));
                }
            }
        }
    }

    public static void broadcastToVisiblePlayers(ProtoMessage<?> message, Entity centerEntity) {
        try (var payload = payload(message);
             var protobuf = ProtocolBuffer.serialize(payload.protobuf());
             var attachment = payload.raw().map(UniBuffer::borrow)
                     .orElseGet(() -> ArrayBuffer.allocate(0))) {
            var spec = messageSpec(payload.protobuf(), MessageDirection.SERVER_TO_CLIENT);
            spec.attachmentPolicy().validate(attachment.size());
            try (var frame = FrameCodec.encode(spec.id(), protobuf, attachment)) {
                synchronized (CHANNEL_NAME) {
                    require().sendToAllTracking(packet(frame), centerEntity);
                }
            }
        }
    }

    /**
     * 对照 {@code PacketDistributor.TRACKING_ENTITY_AND_SELF}：1.12.2 没有这一投递目标，
     * 补一次点对点投递；两次投递各自包一份帧，避免共享 netty 引用计数。
     */
    public static void broadcastToVisiblePlayersAndSelf(ProtoMessage<?> message, EntityPlayer self) {
        try (var payload = payload(message);
             var protobuf = ProtocolBuffer.serialize(payload.protobuf());
             var attachment = payload.raw().map(UniBuffer::borrow)
                     .orElseGet(() -> ArrayBuffer.allocate(0))) {
            var spec = messageSpec(payload.protobuf(), MessageDirection.SERVER_TO_CLIENT);
            spec.attachmentPolicy().validate(attachment.size());
            try (var frame = FrameCodec.encode(spec.id(), protobuf, attachment)) {
                synchronized (CHANNEL_NAME) {
                    var target = require();
                    target.sendToAllTracking(packet(frame), self);
                    if (self instanceof EntityPlayerMP player) {
                        target.sendTo(packet(frame), player);
                    }
                }
            }
        }
    }

    /**
     * 点对点出站（{@code ForgeTransportPort} 的唯一发送路径）。
     * {@code FMLProxyPacket.toS3FPackets} 读 {@code payload.array()} 整个后备数组，
     * 因此必须先按 {@code readableBytes()} 拷出紧凑帧，再 {@code wrappedBuffer} 封装，
     * 不能把按需扩容的缓冲直接交出去。目标属性写在 {@code FMLEmbeddedChannel} 上，
     * 资源分片由工作线程池发出，出站必须串行化。
     */
    public static void sendFrame(EntityPlayerMP player, OutboundFrame frame) {
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        var submitted = false;
        try {
            ForgeUniBufferIO.write(buffer, frame.borrow());
            var bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            synchronized (CHANNEL_NAME) {
                require().sendTo(new FMLProxyPacket(
                        new PacketBuffer(Unpooled.wrappedBuffer(bytes)), CHANNEL_NAME), player);
            }
            submitted = true;
        } finally {
            buffer.release();
            if (!submitted) {
                frame.close();
            }
        }
    }

    private static void send(NetworkPayload<?> payload, EntityPlayerMP player) {
        try (payload; var protobuf = ProtocolBuffer.serialize(payload.protobuf());
             var attachment = payload.raw().map(UniBuffer::borrow)
                     .orElseGet(() -> ArrayBuffer.allocate(0))) {
            var spec = messageSpec(payload.protobuf(), MessageDirection.SERVER_TO_CLIENT);
            spec.attachmentPolicy().validate(attachment.size());
            try (var frame = FrameCodec.encode(spec.id(), protobuf, attachment)) {
                sendFrame(player, frame);
            }
        }
    }

    private static ProtocolMessageSpec<?> messageSpec(
            ProtoMessage<?> message, MessageDirection direction) {
        var spec = ProtocolMessages.REGISTRY.find(message.getClass())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unregistered protocol message type: " + message.getClass().getName()));
        if (spec.direction() != direction) {
            throw new IllegalArgumentException("Protocol message has the wrong network direction");
        }
        return spec;
    }

    private static void receive(FMLNetworkEvent.CustomPacketEvent<?> event,
                                Supplier<NetworkEvent.Context> context,
                                MessageDirection direction) {
        var payload = event.getPacket().payload().duplicate();
        try (var wire = ForgeUniBufferIO.readNative(
                new PacketBuffer(payload), payload.readableBytes());
             var frame = FrameCodec.decode(wire, id -> ProtocolInbound.accepts(id, direction))) {
            var spec = ProtocolMessages.REGISTRY.find(frame.messageId()).orElseThrow();
            ProtocolInbound.dispatch(frame, spec, context);
        } catch (RuntimeException error) {
            YesSteveModel.LOGGER.warn("Rejected invalid YSM frame", error);
            if (direction == MessageDirection.SERVER_TO_CLIENT) {
                var source = context.get();
                var connection = source.getNetworkManager();
                source.enqueueWork(() -> {
                    try {
                        ClientSessionRuntime.failProtocolSession(connection);
                    } catch (IllegalStateException ignored) {
                    }
                });
            }
        } finally {
            context.get().setPacketHandled(true);
        }
    }

    private static FMLEventChannel require() {
        var current = channel;
        if (current == null) {
            throw new IllegalStateException("YSM protocol channel is not registered yet");
        }
        return current;
    }

    /**
     * 每个发送目标各自封装一份帧；{@code wrappedBuffer} 共享底层数组但
     * reader/writer index 独立，netty 引用计数不共享。
     */
    private static FMLProxyPacket packet(OutboundFrame frame) {
        return new FMLProxyPacket(new PacketBuffer(Unpooled.wrappedBuffer(frame.bytes())), CHANNEL_NAME);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static NetworkPayload<?> payload(ProtoMessage<?> message) {
        return NetworkPayload.protobuf((ProtoMessage) message);
    }

    private static final class Inbound {
        private Inbound() {
        }

        @SubscribeEvent
        public void onServerPacket(FMLNetworkEvent.ServerCustomPacketEvent event) {
            receive(event, InboundContext.server(event), MessageDirection.CLIENT_TO_SERVER);
        }

        @SubscribeEvent
        public void onClientPacket(FMLNetworkEvent.ClientCustomPacketEvent event) {
            receive(event, InboundContext.client(event), MessageDirection.SERVER_TO_CLIENT);
        }
    }
}
