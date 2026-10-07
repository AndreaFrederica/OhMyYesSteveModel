package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.config.LoadingStateScreenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(value = Side.CLIENT, modid = YesSteveModel.MOD_ID)
public class RenderLoadingStateScreenEvent {
    @SubscribeEvent
    public void render(RenderGameOverlayEvent.Text event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }

        if (LoadingStateScreenConfig.DISABLE_LOADING_STATE_SCREEN) {
            return;
        }

        var resolution = event.getResolution();
        int screenWidth = resolution.getScaledWidth();
        int screenHeight = resolution.getScaledHeight();
        var font = Minecraft.getMinecraft().fontRenderer;

        // 根据配置决定位置
        LoadingStateScreenConfig.Position position = LoadingStateScreenConfig.LOADING_STATE_POSITION;
        int x, y, barX, barY;
        // 渲染一个 150 长度的进度条
        int barWidth = 150;
        int barHeight = 10;
        switch (position) {
            case TOP_LEFT -> {
                x = 10;
                y = 10;
                barX = 10;
                barY = 22;
            }
            case TOP_CENTER -> {
                x = screenWidth / 2;
                y = 10;
                barX = (screenWidth - barWidth) / 2;
                barY = 22;
            }
            case TOP_RIGHT -> {
                x = screenWidth - 10;
                y = 10;
                barX = screenWidth - 10 - barWidth;
                barY = 22;
            }
            case BOTTOM_LEFT -> {
                x = 10;
                y = screenHeight - 30;
                barX = 10;
                barY = screenHeight - 8 - barHeight;
            }
            case BOTTOM_CENTER -> {
                x = screenWidth / 2;
                y = screenHeight - 85;
                barX = (screenWidth - barWidth) / 2;
                barY = screenHeight - 63 - barHeight;
            }
            case BOTTOM_RIGHT -> {
                x = screenWidth - 10;
                y = screenHeight - 30;
                barX = screenWidth - 10 - barWidth;
                barY = screenHeight - 8 - barHeight;
            }
            default -> {
                x = screenWidth / 2;
                y = 10;
                barX = (screenWidth - barWidth) / 2;
                barY = 22;
            }
        }

        var service = ClientModelService.current().orElse(null);
        if (service == null) {
            return;
        }
        var loading = service.loadingCount();
        if (loading > 0) {
            String text = I18n.format("gui.yes_steve_model.sync_hint.title") + TextFormatting.YELLOW
                    + I18n.format("gui.yes_steve_model.sync_hint.loading_models", loading,
                            service.catalog().models().size());
            this.drawStringAtPosition(font, text, x, y, screenWidth);
        }
    }

    private void drawStringAtPosition(FontRenderer font, String text, int x, int y, int screenWidth) {
        int textWidth = font.getStringWidth(text);
        int drawX = switch (LoadingStateScreenConfig.LOADING_STATE_POSITION) {
            case TOP_LEFT, BOTTOM_LEFT -> x;
            case TOP_CENTER, BOTTOM_CENTER -> (screenWidth - textWidth) / 2;
            case TOP_RIGHT, BOTTOM_RIGHT -> x - textWidth;
        };
        font.drawStringWithShadow(text, drawX, y, 0xFFFFFF);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
