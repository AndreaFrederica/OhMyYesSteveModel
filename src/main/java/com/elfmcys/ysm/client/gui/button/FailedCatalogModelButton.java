package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.model.catalog.client.entry.ClientFailedCatalogEntry;
import com.elfmcys.ysm.util.ModelIdUtil;
import java.util.List;

import com.elfmcys.ysm.util.TextMeasureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextFormatting;

/** Disabled path-only card for a publication entry whose metadata did not activate. */
public final class FailedCatalogModelButton extends Button {
    private final ClientFailedCatalogEntry entry;

    public FailedCatalogModelButton(int x, int y, ClientFailedCatalogEntry entry) {
        super(x, y, 52, 90,
                ModelIdUtil.getFileNameFromPath(entry.displayPath()),
                ignored -> { });
        this.entry = entry;
        enabled = false;
    }

    @Override
    public void renderWidget(Minecraft mc, int mouseX, int mouseY,
                             float partialTick) {
        this.drawGradientRect(x, y, x + width, y + height,
                0xFF2E2E2E, 0xFF2E2E2E);
        this.drawCenteredString(mc.fontRenderer,
                "!", x + width / 2,
                y + (height - 20) / 2, 0xFFE57373);
        var lines = TextMeasureUtil.split(mc.fontRenderer, displayString, 45);
        var y = this.y + height - (lines.size() > 1 ? 19 : 15);
        for (var line : lines.stream().limit(2).toList()) {
            this.drawCenteredString(mc.fontRenderer, line,
                    x + width / 2, y, 0xFF9E9E9E);
            y += 9;
        }
    }

    public void renderTooltip(GuiScreen screen, int mouseX, int mouseY) {
        if (!isMouseOver()) {
            return;
        }
        screen.drawHoveringText(List.of(
                TextFormatting.GRAY + entry.displayPath(),
                TextFormatting.RED + entry.error()),
                mouseX, mouseY);
    }
}
