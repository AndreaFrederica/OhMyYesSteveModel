package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.gui.button.FlatColorButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;

public class DownloadScreen extends Screen {
    private final PlayerModelScreen parent;
    private int x;
    private int y;

    public DownloadScreen(PlayerModelScreen parent) {
        super();
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.x = (width - 420) / 2;
        this.y = (height - 235) / 2;

        addButton(new FlatColorButton(x + 5, y, 80, 18, I18n.format("gui.yes_steve_model.model.return"), (b) -> this.mc.displayGuiScreen(parent)));
    }

    @Override
    public void drawScreen(int pMouseX, int pMouseY, float pPartialTick) {
        drawDefaultBackground();
        this.drawCenteredString(fontRenderer, TextFormatting.DARK_RED + "Coming Soooooooooooooooooooooooooon™", width / 2, height / 2 - 5, 0xffffffff);
        super.drawScreen(pMouseX, pMouseY, pPartialTick);
    }
}
