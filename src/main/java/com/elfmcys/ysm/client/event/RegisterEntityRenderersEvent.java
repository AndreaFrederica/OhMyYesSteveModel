package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.client.renderer.CustomFirstPersonArmRenderer;
import com.elfmcys.ysm.client.renderer.CustomPlayerRenderer;
import com.elfmcys.ysm.client.renderer.CustomProjectileRenderer;
import com.elfmcys.ysm.client.renderer.CustomVehicleRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.resources.IReloadableResourceManager;

public class RegisterEntityRenderersEvent {
    private static CustomPlayerRenderer CUSTOM_PLAYER_RENDERER;
    private static CustomProjectileRenderer CUSTOM_PROJECTILE_RENDERER;
    private static CustomFirstPersonArmRenderer CUSTOM_FIRST_PERSON_RENDERER;
    private static CustomVehicleRenderer CUSTOM_VEHICLE_RENDERER;

    private static void init() {
        RenderManager context = Minecraft.getMinecraft().getRenderManager();
        // 注册重载监听时会被立即回调一次，此时 renderManager 可能尚未创建
        //noinspection ConstantValue
        if (context == null) {
            return;
        }

        CUSTOM_PLAYER_RENDERER = new CustomPlayerRenderer(context);
        CUSTOM_PROJECTILE_RENDERER = new CustomProjectileRenderer(context);
        CUSTOM_FIRST_PERSON_RENDERER = new CustomFirstPersonArmRenderer();
        CUSTOM_VEHICLE_RENDERER = new CustomVehicleRenderer(context);

        /*
        SophisticatedCompat.addLayer();
        */
    }

    public static void registerReloadListener() {
        ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager())
                .registerReloadListener(_ -> init());
    }

    public static CustomPlayerRenderer getPlayerRenderer() {
        if (CUSTOM_PLAYER_RENDERER == null) {
            init();
        }
        return CUSTOM_PLAYER_RENDERER;
    }

    public static CustomProjectileRenderer getProjectRenderer() {
        if (CUSTOM_PROJECTILE_RENDERER == null) {
            init();
        }
        return CUSTOM_PROJECTILE_RENDERER;
    }

    public static CustomFirstPersonArmRenderer getFirstPersonArmRenderer() {
        if (CUSTOM_FIRST_PERSON_RENDERER == null) {
            init();
        }
        return CUSTOM_FIRST_PERSON_RENDERER;
    }

    public static CustomVehicleRenderer getVehicleRenderer() {
        if (CUSTOM_VEHICLE_RENDERER == null) {
            init();
        }
        return CUSTOM_VEHICLE_RENDERER;
    }
}
