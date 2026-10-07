package com.elfmcys.ysm.client.gui.button;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class FlatColorButton extends Button {
    private boolean isSelect = false;
    private List<String> tooltips;

    public FlatColorButton(int pX, int pY, int pWidth, int pHeight, String pMessage, Consumer<Button> pOnPress) {
        super(pX, pY, pWidth, pHeight, pMessage, pOnPress);
    }

    public FlatColorButton setTooltips(String key) {
        tooltips = Collections.singletonList(I18n.format(key));
        return this;
    }

    public FlatColorButton setTooltips(List<String> tooltips) {
        this.tooltips = tooltips;
        return this;
    }

    public void renderToolTip(GuiScreen screen, int pMouseX, int pMouseY) {
        if (this.isMouseOver() && tooltips != null) {
            screen.drawHoveringText(tooltips, pMouseX, pMouseY);
        }
    }

    @Override
    public void renderWidget(Minecraft mc, int mouseX, int mouseY, float pPartialTick) {
        if (isSelect) {
            this.drawGradientRect(this.x, this.y, this.x + this.width, this.y + this.height, 0xff_1E90FF, 0xff_1E90FF);
        } else {
            this.drawGradientRect(this.x, this.y, this.x + this.width, this.y + this.height, 0xff_434242, 0xff_434242);
        }
        if (this.isMouseOver()) {
            this.drawGradientRect(this.x, this.y + 1, this.x + 1, this.y + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.x, this.y, this.x + this.width, this.y + 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.x + this.width - 1, this.y + 1, this.x + this.width, this.y + this.height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
            this.drawGradientRect(this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, 0xff_F3EFE0, 0xff_F3EFE0);
        }
        this.renderScrollingString(mc.fontRenderer, 2, 0xF3EFE0);
    }

    public void setSelect(boolean select) {
        isSelect = select;
    }
}
