package com.elfmcys.ysm.client.gui.button;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class FlatRatioBox extends Button implements IConfigFormsButton {
    private final int maxHeight;

    public FlatRatioBox(int pX, int pY, int maxHeight, String component) {
        super(pX, pY, 115, 15, component, _ -> {});
        this.maxHeight = maxHeight;
    }

    @Override
    protected void renderWidget(Minecraft mc, int pMouseX, int pMouseY, float pPartialTick) {
        drawRect(this.x, this.y, this.x + this.width, this.y + maxHeight, 0xef_434242);
        this.renderScrollingString(mc.fontRenderer, 2, 0xFFFFFF);
    }
}
