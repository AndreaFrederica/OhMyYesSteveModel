package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.util.GuiRenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.client.config.GuiUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * 改良版的按钮，具有新版按钮长文本滚动的特性，同时舍弃了 ID 系统（ID 默认 -1），改为和新版类似的 lamba。
 */
// TODO：重构组件 Tooltip
public class Button extends GuiButton {
    public static final int ID = -1;
    protected final Consumer<Button> onPress;
    protected @Nullable List<String> tooltip;

    public Button(int id, int x, int y, int width, int height, String message, Consumer<Button> onPress) {
        super(id, x, y, width, height, message);
        this.onPress = onPress;
    }

    public Button(int x, int y, int width, int height, String message, Consumer<Button> onPress) {
        this(-1, x, y, width, height, message, onPress);
    }

    @Override
    public void drawButton(@NotNull Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!this.visible) return;
        this.hovered = mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width && mouseY < this.y + this.height;
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        this.renderWidget(mc, mouseX, mouseY, partialTicks);
        this.mouseDragged(mc, mouseX, mouseY);
    }

    protected void renderWidget(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        mc.getTextureManager().bindTexture(BUTTON_TEXTURES);
        int offset = this.getHoverState(this.isMouseOver());
        GuiUtils.drawContinuousTexturedBox(this.x, this.y, 0, 46 + offset * 20, this.width, this.height, 200, 20, 4, 4, 20, 20, this.zLevel);
        this.renderScrollingString(mc.fontRenderer, 2, this.getFGColor());
    }

    @SuppressWarnings("SameParameterValue")
    protected void renderScrollingString(FontRenderer font, int border, int color) {
        GuiRenderUtil.drawScrollingString(font, this.getMessage(),
                this.x + border, this.y,
                this.x + this.width - border, this.y + this.height,
                color, true);
    }

    protected String getMessage() {
        return this.displayString;
    }

    protected int getFGColor() {
        if (this.packedFGColour != 0) {
            return this.packedFGColour;
        } else if (!this.enabled) {
            return 0xA0A0A0;
        } else if (this.hovered) {
            return 0xFFFFA0;
        } else {
            return 0xE0E0E0;
        }
    }

    /**
     * 应被 Gui 调用，而不是按钮自我调用。
     */
    public void onPress() {
        this.onPress.accept(this);
    }

    public void setTooltip(@Nullable List<String> tooltip) {
        this.tooltip = tooltip;
    }

    public void setTooltip(String tooltip) {
        this.tooltip = List.of(tooltip);
    }

    @Nullable
    public List<String> getTooltip() {
        return this.tooltip;
    }

    @Override
    public void drawCenteredString(@NotNull FontRenderer font, @NotNull String text, int x, int y, int color) {
        super.drawCenteredString(font, text, x, y, color);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void drawString(@NotNull FontRenderer font, @NotNull String text, int x, int y, int color) {
        super.drawString(font, text, x, y, color);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
