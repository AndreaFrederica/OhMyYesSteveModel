package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(value = Side.CLIENT)
public class ClientLoggedEvent {
    private static NetworkManager loggedIn;

    public static void onPlayerLoggedIn() {
        var handler = Minecraft.getMinecraft().getConnection();
        if (handler == null) {
            return;
        }
        var connection = handler.getNetworkManager();
        if (loggedIn == connection) {
            return;
        }
        if (!YesSteveModel.isAvailable()) {
            loggedIn = connection;
            YesSteveModel.sendUnavailableMessage();
            return;
        }
        ClientSessionRuntime.beginConnection(connection);
        loggedIn = connection;
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        var connection = event.getManager();
        if (loggedIn != connection) {
            return;
        }
        loggedIn = null;
        if (YesSteveModel.isAvailable()) {
            Minecraft.getMinecraft().addScheduledTask(
                    () -> ClientSessionRuntime.disconnect(connection));
        }
    }
}
