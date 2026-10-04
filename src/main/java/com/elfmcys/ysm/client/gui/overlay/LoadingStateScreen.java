package com.elfmcys.ysm.client.gui.overlay;

import com.elfmcys.ysm.config.LoadingStateScreenConfig;
import com.elfmcys.ysm.model.catalog.CatalogScanProgress;
import com.elfmcys.ysm.model.service.ClientModelService;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

public class LoadingStateScreen implements IGuiOverlay {
    private static CatalogScanProgress lastProgress = CatalogScanProgress.IDLE;
    private static long lastChange;

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
        // Screens draw over the HUD. Their Post event draws this panel once, above the screen.
        if (Minecraft.getInstance().screen == null) renderProgress(graphics, width, height);
    }

    public static void renderProgress(GuiGraphics graphics, int width, int height) {
        if (LoadingStateScreenConfig.DISABLE_LOADING_STATE_SCREEN.get()) return;
        var service = ClientModelService.current().orElse(null);
        if (service == null) return;
        var progress = service.scanProgress();
        var now = Util.getMillis();
        if (!progress.equals(lastProgress)) {
            lastProgress = progress;
            lastChange = now;
        }
        var showScan = progress.active() || (progress.stage() != CatalogScanProgress.Stage.IDLE
                && now - lastChange < 5_000);
        var mesh = service.meshLoadProgress();
        var showMesh = mesh.active();
        var loading = service.loadingCount();
        if (!showScan && !showMesh && loading == 0) return;

        var panelWidth = Math.min(360, width - 20);
        // Mesh loads expose a main stage, a sub-operation, the current source,
        // two progress dimensions and the worker counters. Keep the panel tall
        // enough that a long MMD/VRM decode never looks like a frozen spinner.
        var panelHeight = showMesh ? 82 : showScan ? (loading > 0 ? 63 : 51) : 27;
        var position = LoadingStateScreenConfig.LOADING_STATE_POSITION.get();
        var x = switch (position) {
            case TOP_LEFT, BOTTOM_LEFT -> 10;
            case TOP_RIGHT, BOTTOM_RIGHT -> width - panelWidth - 10;
            default -> (width - panelWidth) / 2;
        };
        var y = switch (position) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> 10;
            case BOTTOM_CENTER -> Math.max(10, height - panelHeight - 55);
            default -> Math.max(10, height - panelHeight - 10);
        };
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xCC101820);
        var title = showMesh ? Component.literal("Oh My YSM · " + mesh.stage()
                + " · " + mesh.operation()) : showScan ? switch (progress.stage()) {
            case DISCOVERING -> label("discovering");
            case FINALIZING -> label("finalizing", progress.completed(), progress.total());
            case COMPLETE -> label("complete", progress.completed(), progress.total());
            case FAILED -> label("failed", progress.completed(), progress.total());
            default -> label("progress", progress.completed(), progress.total());
        } : label("runtime", loading, service.activeWorkerCount(), service.queuedTaskCount());
        line(graphics, title, x + 5, y + 4, panelWidth - 10);
        int barX = x + 5, barY = y + 16, barWidth = panelWidth - 10;
        graphics.fill(barX, barY, barX + barWidth, barY + 5, 0xFF404850);
        if (showMesh) {
            fillMeshProgress(graphics, barX, barY, 5, barWidth, mesh.completedItems(), mesh.totalItems(),
                    mesh.completed(), mesh.total(), 0xFF60C8A0);
            int subBarY = barY + 7;
            graphics.fill(barX, subBarY, barX + barWidth, subBarY + 3, 0xFF303840);
            fillMeshProgress(graphics, barX, subBarY, 3, barWidth, 0, 0,
                    mesh.completed(), mesh.total(), 0xFF70B8FF);
        } else if (showScan && progress.stage() != CatalogScanProgress.Stage.DISCOVERING) {
            int filled = progress.total() == 0 ? barWidth
                    : (int) ((long) barWidth * progress.completed() / progress.total());
            graphics.fill(barX, barY, barX + filled, barY + 5,
                    progress.stage() == CatalogScanProgress.Stage.FAILED ? 0xFFFF8060 : 0xFF60C8A0);
        } else {
            int segment = barWidth / 5;
            int offset = (int) ((now / 12) % (barWidth - segment + 1));
            graphics.fill(barX + offset, barY, barX + offset + segment, barY + 5, 0xFF70B8FF);
        }
        if (showMesh) {
            line(graphics, Component.literal("file: " + mesh.currentFile()), x + 5, y + 30, panelWidth - 10);
            line(graphics, Component.literal("items: " + mesh.completedItems() + " / "
                    + (mesh.totalItems() == 0 ? "?" : mesh.totalItems())), x + 5, y + 42, panelWidth - 10);
            line(graphics, Component.literal("data: " + formatBytes(mesh.completed()) + " / "
                    + (mesh.total() == 0 ? "?" : formatBytes(mesh.total()))), x + 5, y + 54, panelWidth - 10);
            line(graphics, Component.literal("model: " + mesh.model()), x + 5, y + 66, panelWidth - 10);
        } else if (showScan) {
            line(graphics, label("queue", progress.waiting(), progress.inFlight(), progress.errors()),
                    x + 5, y + 26, panelWidth - 10);
            line(graphics, label("operation", progress.phase(), progress.currentPath()),
                    x + 5, y + 38, panelWidth - 10);
            if (loading > 0) line(graphics, label("runtime", loading, service.activeWorkerCount(),
                    service.queuedTaskCount()), x + 5, y + 50, panelWidth - 10);
        }
        graphics.pose().popPose();
    }

    private static Component label(String key, Object... args) {
        return Component.translatable("gui.yes_steve_model.loading." + key, args);
    }

    private static void fillMeshProgress(GuiGraphics graphics, int x, int y, int height, int width,
                                         int completedItems, int totalItems,
                                         long completed, long total, int color) {
        long done = totalItems > 0 ? completedItems : completed;
        long max = totalItems > 0 ? totalItems : total;
        if (max > 0) {
            int filled = (int) Math.min(width, Math.max(0, (long) width * done / max));
            graphics.fill(x, y, x + filled, y + height, color);
        } else {
            int segment = Math.max(8, width / 5);
            int offset = (int) ((Util.getMillis() / 12) % Math.max(1, width - segment + 1));
            graphics.fill(x + offset, y, x + offset + segment, y + height, color);
        }
    }

    private static String formatBytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return (value / 1024) + " KiB";
        if (value < 1024L * 1024 * 1024) return (value / (1024 * 1024)) + " MiB";
        return (value / (1024L * 1024 * 1024)) + " GiB";
    }

    private static void line(GuiGraphics graphics, Component text, int x, int y, int width) {
        var font = Minecraft.getInstance().font;
        float scale = Math.min(1F, (float) width / Math.max(1, font.width(text)));
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, 0xFFFFFF);
        graphics.pose().popPose();
    }
}
