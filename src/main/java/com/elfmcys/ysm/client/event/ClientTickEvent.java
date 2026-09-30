package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.client.texture.CustomTextureManager;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import com.elfmcys.ysm.network.forge.PlayerStateHandler;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ClientTickEvent {
    private static int tickCount;
    private static int refreshRate = 60;

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            CustomTextureManager.uploadFrame();
            ClientModelService.current().ifPresent(ClientModelService::beginRenderFrame);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (event.phase == TickEvent.Phase.END) {
            return;
        }
        tickCount++;
        PlayerStateHandler.tickClient();
        CustomTextureManager.tick();
        ClientModelService.current().ifPresent(ClientModelService::tick);
        refreshRate = Minecraft.getInstance().getWindow().getRefreshRate();

        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(capability -> {
                capability.handleRoamingVarsChanges();
                ClientProtocolGateway.tick(player, capability);
            });
        }
    }

    public static int getTickCount() {
        return tickCount;
    }

    public static int getRefreshRate() {
        return refreshRate;
    }
}
