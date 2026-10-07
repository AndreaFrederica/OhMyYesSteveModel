package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.gui.button.Button;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSources;
import com.elfmcys.ysm.util.GuiRenderUtil;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.I18n;

public class OpenModelFolderScreen extends Screen {
    private final PlayerModelScreen screen;

    protected OpenModelFolderScreen(PlayerModelScreen screen) {
        super();
        this.screen = screen;
    }

    @Override
    public void initGui() {
        int x = (width - 310) / 2;
        int y = height / 2 + 60;
        this.addButton(new Button(x, y, 150, 20, I18n.format("gui.yes_steve_model.open_model_folder.open"), b -> {
            OpenGlHelper.openFile(ModelCatalogSources.customPath().toFile());
        }));
        this.addButton(new Button(x + 160, y, 150, 20, I18n.format("gui.yes_steve_model.model.return"), b -> {
            mc.displayGuiScreen(this.screen);
        }));
    }

    @Override
    public void drawScreen(int pMouseX, int pMouseY, float pPartialTick) {
        drawDefaultBackground();
        GuiRenderUtil.drawWordWrap(fontRenderer, I18n.format("gui.yes_steve_model.open_model_folder.tips"),
                (width - 400) / 2, height / 2 - 80, 0XFFFFFF, true, 400, 0);
        super.drawScreen(pMouseX, pMouseY, pPartialTick);
    }
}
