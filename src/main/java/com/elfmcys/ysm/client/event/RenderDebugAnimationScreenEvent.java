package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.ProjectileAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
/* 1.12.2 迁移暂缓：车万女仆（touhou-little-maid）兼容整体未迁移
import com.elfmcys.ysm.client.compat.touhoulittlemaid.capability.YsmMaidCapabilityProvider;
*/
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.elfmcys.ysm.client.entity.CustomEntity;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import com.elfmcys.ysm.geckolib3.core.processor.DebugInfo;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;

@Mod.EventBusSubscriber(value = Side.CLIENT, modid = YesSteveModel.MOD_ID)
public class RenderDebugAnimationScreenEvent {
    private static final DebugInfo DEBUG_INFO = new DebugInfo();
    private static final ReferenceArrayList<String> DEBUG_CONTROLLERS = new ReferenceArrayList<>();
    private static WeakReference<CustomEntity<?>> TARGET = null;

    public static DebugInfo getDebugInfo() {
        return DEBUG_INFO;
    }

    public static boolean isEnabled() {
        return getTarget() != null;
    }

    public static boolean enable() {
        var result = Minecraft.getMinecraft().objectMouseOver;
        if (result != null && result.typeOfHit == RayTraceResult.Type.ENTITY && result.entityHit != null) {
            return enable(result.entityHit);
        }
        return enableForLocalPlayer();
    }

    public static boolean enableForLocalPlayer() {
        var player = Minecraft.getMinecraft().player;
        if (player != null) {
            var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
            if (cap != null) enable(cap);
            return true;
        }
        disable();
        return false;
    }

    public static boolean enable(Entity entity) {
        CustomEntity<?> cap;
        if (entity instanceof EntityPlayer) {
            cap = entity.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
        /*
        } else if (TlmClientCompat.isMaid(entity)) {
            cap = entity.getCapability(YsmMaidCapabilityProvider.CAP, null);
        */
        } else if (entity instanceof IProjectile) {
            cap = entity.getCapability(ProjectileAnimatableCapabilityProvider.CAP, null);
        } else {
            cap = entity.getCapability(VehicleAnimatableCapabilityProvider.CAP, null);
        }
        if (cap != null) {
            RenderDebugAnimationScreenEvent.enable(cap);
            return true;
        } else {
            disable();
            return false;
        }
    }

    public static void enable(CustomEntity<?> customEntity) {
        disable();
        TARGET = new WeakReference<>(customEntity);
        customEntity.setDebugInfo(DEBUG_INFO);
        var entity = customEntity.getEntity();
        var localPlayer = Minecraft.getMinecraft().player;
        if (localPlayer != null) {
            localPlayer.sendMessage(new TextComponentTranslation("message.yes_steve_model.model.debug_animation.true")
                    .appendText(" -> ")
                    .appendSibling(entity.hasCustomName() ? new TextComponentString(entity.getCustomNameTag()) : entity.getDisplayName()));
        }
    }

    public static void disable() {
        if (TARGET != null) {
            var target = TARGET.get();
            if (target != null) {
                target.setDebugInfo(null);
            }
            TARGET = null;
            var localPlayer = Minecraft.getMinecraft().player;
            if (localPlayer != null) {
                localPlayer.sendMessage(new TextComponentTranslation("message.yes_steve_model.model.debug_animation.false"));
            }
        }
    }

    public static void addDebugController(String controllerName) {
        DEBUG_CONTROLLERS.add(0, controllerName);
    }

    public static void clearDebugController() {
        DEBUG_CONTROLLERS.clear();
    }

    @Nullable
    public static CustomEntity<?> getTarget() {
        if (TARGET != null){
            var entity = TARGET.get();
            if (entity != null && entity.isActive()) {
                return entity;
            }
            disable();
        }
        return null;
    }

    @SuppressWarnings({"UnnecessaryLocalVariable", "rawtypes"})
    @SubscribeEvent
    public static void renderCustom(RenderGameOverlayEvent.Text event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }

        CustomEntity<?> target = getTarget();
        if (target == null) {
            return;
        }

        var resolution = event.getResolution();
        int screenWidth = resolution.getScaledWidth();
        int screenHeight = resolution.getScaledHeight();
        var font = Minecraft.getMinecraft().fontRenderer;

        int[] y = {5};

        DebugInfo debugInfo = DEBUG_INFO;
        debugInfo.enumerate((name, result) -> {
            renderCustomText(font, y, name, result, screenWidth, screenHeight);
        });

        // 渲染状态机信息
        DEBUG_CONTROLLERS.forEach(name -> {
            IAnimationController controller = target.getAnimationData().getAnimationController(name);
            renderCustomText(font, y, name, controller != null ? controller.getStateName() : "(N/A)", screenWidth, screenHeight);
        });
    }

    private static void renderCustomText(FontRenderer font, int[] y, String name, String result, int screenWidth, int screenHeight) {
        if ((y[0] - 5) % 20 == 0) {
            Gui.drawRect(2, y[0] - 1, screenWidth, y[0] + 9, 0xc0505050);
        } else {
            Gui.drawRect(2, y[0] - 1, screenWidth, y[0] + 9, 0xc0506050);
        }
        font.drawString(name, 5, y[0], 0xffffff);
        font.drawString(result, screenWidth / 2, y[0], 0xffffff);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        y[0] = y[0] + 10;
    }
}
