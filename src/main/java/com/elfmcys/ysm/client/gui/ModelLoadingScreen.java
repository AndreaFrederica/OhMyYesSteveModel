package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.config.ModelLoadingConfig;
import com.elfmcys.ysm.model.service.ClientModelService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.widget.ForgeSlider;
import net.minecraftforge.common.ForgeConfigSpec;

/** Small, separate settings page so the existing model options do not grow off screen. */
public final class ModelLoadingScreen extends Screen {
    private final Screen parent;

    public ModelLoadingScreen(Screen parent) {
        super(label("settings"));
        this.parent = parent;
    }

    private static Component label(String key, Object... args) {
        return Component.translatable("gui.yes_steve_model.loading." + key, args);
    }

    @Override
    protected void init() {
        slider(36, "catalog_workers", ModelLoadingConfig.CATALOG_WORKERS, 1, 8);
        slider(58, "client_workers", ModelLoadingConfig.CLIENT_WORKERS, 1, 8);
        slider(80, "queued_inputs", ModelLoadingConfig.QUEUED_INPUTS, 0, 64);
        slider(102, "publications", ModelLoadingConfig.PUBLICATIONS_PER_TICK, 1, 32);
        slider(124, "millis", ModelLoadingConfig.PUBLICATION_MILLIS, 1, 10);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 60, height - 26, 120, 20).build());
    }

    private void slider(int y, String key, ForgeConfigSpec.IntValue setting, int min, int max) {
        var controlWidth = Math.min(320, width - 20);
        addRenderableWidget(new ForgeSlider((width - controlWidth) / 2, y, controlWidth, 20,
                label(key), Component.empty(), min, max, setting.get(), 1, 0, true) {
            @Override
            protected void applyValue() {
                setting.set(getValueInt());
            }
        });
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        centeredLine(graphics, label("timing"), 150, 0xAAAAAA);
        centeredLine(graphics, label("budget_hint"), 162, 0xAAAAAA);
        ClientModelService.current().ifPresent(service -> {
            var scan = service.scanProgress();
            centeredLine(graphics, label("queue", scan.waiting(), scan.inFlight(),
                    scan.errors()), 178, 0xFFFFFF);
            centeredLine(graphics, label("runtime", service.loadingCount(),
                    service.activeWorkerCount(), service.queuedTaskCount()), 190, 0xFFFFFF);
        });
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void centeredLine(GuiGraphics graphics, Component text, int y, int color) {
        float scale = Math.min(1F, (float) (width - 20) / Math.max(1, font.width(text)));
        graphics.pose().pushPose();
        graphics.pose().translate(width / 2F, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawCenteredString(font, text, 0, 0, color);
        graphics.pose().popPose();
    }

    @Override
    public void removed() {
        ModelLoadingConfig.CATALOG_WORKERS.save();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
