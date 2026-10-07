package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.YesSteveModel;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 1.12.2 版 {@code net.minecraftforge.network.NetworkEvent}：只还原 YSM 用到的四个 Context 成员，
 * 使各 handler 的 {@code Supplier<NetworkEvent.Context>} 签名与调用点与 1.20.1 上游保持一致。
 */
public final class NetworkEvent {
    private NetworkEvent() {
    }

    public static final class Context {
        private final NetworkManager manager;
        private final Side receptionSide;

        Context(NetworkManager manager, Side receptionSide) {
            this.manager = Objects.requireNonNull(manager, "manager");
            this.receptionSide = Objects.requireNonNull(receptionSide, "receptionSide");
        }

        /** 对照 1.20.1 NetworkEvent.Context#getSender：只有服务端收包时才有发送者。 */
        public @Nullable EntityPlayerMP getSender() {
            return manager.getNetHandler() instanceof NetHandlerPlayServer handler
                    ? handler.player : null;
        }

        public NetworkManager getNetworkManager() {
            return manager;
        }

        public Side getReceptionSide() {
            return receptionSide;
        }

        /**
         * 对照 1.20.1 NetworkEvent.Context#enqueueWork：切到接收侧主线程。
         * {@code getWorldThread} 是 FML 为收包线程切换准备的边无关入口，
         * 1.12.2 的 {@code addScheduledTask} 已自带“本就在主线程则直接执行”的快路径。
         */
        public void enqueueWork(Runnable runnable) {
            var handler = manager.getNetHandler();
            var listener = handler == null ? null
                    : FMLCommonHandler.instance().getWorldThread(handler);
            if (listener == null) {
                YesSteveModel.LOGGER.debug("Dropped YSM protocol work because the {} thread is gone",
                        receptionSide);
                return;
            }
            listener.addScheduledTask(runnable);
        }

        /** 1.12.2 无“包已处理”标记，保留空实现以维持上游调用点。 */
        @SuppressWarnings("unused")
        public void setPacketHandled(boolean handled) {
        }
    }
}
