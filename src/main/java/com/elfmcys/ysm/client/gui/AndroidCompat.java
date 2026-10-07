package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.gui.button.Button;
import com.elfmcys.ysm.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 为了兼容 Android 平台运行 Java 版 MC
 * <p>
 * 设计此类用于一些 Android 特有的兼容性处理
 */
@Mod.EventBusSubscriber(value = Side.CLIENT, modid = YesSteveModel.MOD_ID)
public class AndroidCompat {
    public static boolean isAndroid() {
        return YesSteveModel.isMobilePlatform();
    }

    @SubscribeEvent
    public static void addYsmSkinButton(GuiScreenEvent.InitGuiEvent.Post event) {
        if (AndroidCompat.isAndroid() && event.getGui() instanceof GuiIngameMenu screen) {
            Minecraft mc = screen.mc;

            String skinName = I18n.format("gui.yes_steve_model.skin");
            Button skinButton = new Button(screen.width / 2 - 69, screen.height - 35, 138, 30,
                    skinName, button -> {
                if (ClientConfig.DISCLAIMER_SHOW) {
                    mc.displayGuiScreen(new DisclaimerScreen());
                } else {
                    mc.displayGuiScreen(new PlayerModelScreen());
                }
            });
            skinButton.setTooltip(I18n.format("key.yes_steve_model.player_model.desc"));

            String configIcon = "\uD83D\uDD27";
            Button extraPlayerButton = new Button(screen.width / 2 - 120, screen.height - 35, 50, 30,
                    configIcon, button -> mc.displayGuiScreen(new ExtraPlayerConfigScreen()));
            extraPlayerButton.setTooltip(I18n.format("key.yes_steve_model.open_extra_player_render.desc"));

            String icon = "\uD83D\uDE04";
            Button rouletteButton = new Button(screen.width / 2 + 69, screen.height - 35, 50, 30,
                    icon, button -> {
                if (mc.player != null) {
                    var cap = mc.player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
                    if (cap != null) {
                        String modelId = cap.getModelId();
                        var model = cap.getModelRenderTarget();
                        if (model != null && !model.info().getExtraAnimations().isEmpty()) {
                            mc.displayGuiScreen(new AnimationRouletteScreen(modelId, model, cap));
                        }
                    }
                }
            });
            rouletteButton.setTooltip(I18n.format("key.yes_steve_model.animation_roulette.desc"));

            var list = event.getButtonList();
            list.add(skinButton);
            list.add(extraPlayerButton);
            list.add(rouletteButton);
        }
    }

    @SubscribeEvent
    public static void actYsmSkinButton(GuiScreenEvent.ActionPerformedEvent event) {
        if (AndroidCompat.isAndroid() && event.getGui() instanceof GuiIngameMenu
                && event.getButton() instanceof Button button && button.id == -1) {
            button.onPress();
        }
    }

    @SubscribeEvent
    public static void renderYsmSkinButtonTooltip(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (AndroidCompat.isAndroid() && event.getGui() instanceof GuiIngameMenu screen) {
            Screen.renderTooltip(screen, event.getMouseX(), event.getMouseY());
        }
    }
}
