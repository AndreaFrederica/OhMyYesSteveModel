package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.gui.button.Button;
import com.elfmcys.ysm.client.gui.button.Checkbox;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.config.Config;
import com.elfmcys.ysm.util.GuiRenderUtil;
import com.elfmcys.ysm.util.TextMeasureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class DisclaimerScreen extends Screen {
    private Checkbox readCheckbox;
    private int x;
    private int y;

    public DisclaimerScreen() {
        super();
    }

    @Override
    public void initGui() {
        String mainText = I18n.format("gui.yes_steve_model.disclaimer.text");
        List<String> splitMainText = TextMeasureUtil.split(fontRenderer, mainText, 400);
        int totalHeight = TextMeasureUtil.getListStringHeight(fontRenderer, splitMainText, 0) + 20 + 20 + 10 + 20;
        this.x = (width - 400) / 2;
        this.y = (height - totalHeight) / 2;

        String readCheckboxText = I18n.format("gui.yes_steve_model.disclaimer.read");
        int readTextWidth = fontRenderer.getStringWidth(readCheckboxText);
        readCheckbox = new Checkbox((width - readTextWidth) / 2, y + totalHeight - 50, readCheckboxText, this.fontRenderer, !ClientConfig.DISCLAIMER_SHOW);
        addButton(readCheckbox);
        addButton(new Button((width - 300) / 2, y + totalHeight - 20, 300, 20, I18n.format("gui.yes_steve_model.disclaimer.close"), b -> {
            if (readCheckbox.selected()) {
                ClientConfig.DISCLAIMER_SHOW = false;
                this.mc.displayGuiScreen(new PlayerModelScreen());
            } else {
                this.mc.displayGuiScreen(null);
            }
        }));
    }

    @Override
    public void drawScreen(int pMouseX, int pMouseY, float pPartialTick) {
        drawDefaultBackground();
        GuiRenderUtil.drawWordWrap(fontRenderer, I18n.format("gui.yes_steve_model.disclaimer.text"), x, y, 0xffffffff, true, 400, 0);
        super.drawScreen(pMouseX, pMouseY, pPartialTick);
    }

    @Override
    public void onGuiClosed() {
        Config.save();
        super.onGuiClosed();
    }

    @Override
    public void onResize(@NotNull Minecraft mc, int width, int height) {
        Config.save();
        super.onResize(mc, width, height);
    }
}
