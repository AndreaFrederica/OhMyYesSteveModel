package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

public class Checkbox extends Button {
    private static final ResourceLocation CHECKBOX = new ResourceLocation(YesSteveModel.MOD_ID, "texture/checkbox.png");
    private static final int BOX_SIZE = 20; // 方框区域宽度，不包括文本
    private boolean selected;

    /**
     * @param font 计算宽度用，文本较长时按钮宽度（点击区域）也会跟着变长。
     */
    public Checkbox(int x, int y, String message, @NotNull FontRenderer font, boolean selected, Consumer<Button> onPress) {
        super(x, y, BOX_SIZE + 4 + font.getStringWidth(message), BOX_SIZE, message, onPress);
        this.selected = selected;
    }

    /**
     * @param font 计算宽度用，文本较长时按钮宽度（点击区域）也会跟着变长。
     */
    public Checkbox(int x, int y, String message, @NotNull FontRenderer font, boolean selected) {
        this(x, y, message, font, selected, _ -> {
        });
    }

    @Override
    protected void renderWidget(@NotNull Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        int totalWidth = this.x + BOX_SIZE + 4;
        int totalHeight = this.y + (this.height >> 1) - 4;
        mc.getTextureManager().bindTexture(CHECKBOX);
        drawScaledCustomSizeModalRect(
                this.x, this.y,
                this.isMouseOver() ? 20 : 0, this.selected() ? 20 : 0,
                20, 20,
                BOX_SIZE, BOX_SIZE,
                40, 40
        );
        this.drawString(mc.fontRenderer, this.displayString, totalWidth, totalHeight, 0xE0E0E0);
    }

    @Override
    public void onPress() {
        this.selected = !this.selected;
        super.onPress();
    }

    public boolean selected() {
        return this.selected;
    }
}
