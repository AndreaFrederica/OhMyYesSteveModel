package com.elfmcys.ysm.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.List;

@SideOnly(Side.CLIENT)
public final class GuiRenderUtil {
    private static final double PERIOD_PER_SCROLLED_PIXEL = 0.5D;
    private static final double MIN_SCROLL_PERIOD = 3.0D;

    public static void enableScissor(int screenX, int screenY, int boxWidth, int boxHeight) {
        final Minecraft mc = Minecraft.getMinecraft();
        int scale = new ScaledResolution(mc).getScaleFactor();

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
                screenX * scale,
                mc.displayHeight - (screenY * scale + boxHeight * scale),
                Math.max(0, boxWidth * scale),
                Math.max(0, boxHeight * scale)
        );
    }

    public static void disableScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /**
     * Draws a string centered in a box, scrolling horizontally when it is too wide.
     *
     * @param minX left edge of the box.
     * @param minY top edge of the box.
     * @param maxX right edge of the box.
     * @param maxY bottom edge of the box.
     */
    public static void drawScrollingString(
            FontRenderer font, String text,
            int minX, int minY, int maxX, int maxY,
            int color, boolean shadow
    ) {
        drawScrollingString(font, text, (minX + maxX) / 2, minX, minY, maxX, maxY, color, shadow);
    }

    /**
     * Draws a string around a center point in a box, scrolling horizontally when it is too wide.
     *
     * @param centerX horizontal center used when the string fits in the box.
     * @param minX    left edge of the box.
     * @param minY    top edge of the box.
     * @param maxX    right edge of the box.
     * @param maxY    bottom edge of the box.
     */
    public static void drawScrollingString(
            FontRenderer font, String text,
            int centerX, int minX, int minY, int maxX, int maxY,
            int color, boolean shadow
    ) {
        int textWidth = font.getStringWidth(text);
        int textY = (minY + maxY - 9) / 2 + 1;
        int boxWidth = maxX - minX;
        if (textWidth > boxWidth) {
            int overflow = textWidth - boxWidth;
            double time = Minecraft.getSystemTime() / 1000.0D;
            double period = Math.max(overflow * PERIOD_PER_SCROLLED_PIXEL, MIN_SCROLL_PERIOD);
            double progress = Math.sin((Math.PI / 2D) * Math.cos((Math.PI * 2D) * time / period)) / 2D + 0.5D;
            double offset = overflow * progress;
            enableScissor(minX, minY, maxX - minX, maxY - minY);
            font.drawString(text, minX - (int) offset, textY, color, shadow);
            disableScissor();
        } else {
            int clampedX = MathHelper.clamp(centerX, minX + textWidth / 2, maxX - textWidth / 2);
            int textX = clampedX - font.getStringWidth(text) / 2;
            font.drawString(text, textX, textY, color, shadow);
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Split text to a maximum width, then draw them line by line.
     *
     * @param spacing line spacing in pixels.
     * @return the total height rendered in pixels.
     */
    public static int drawWordWrap(
            FontRenderer font, @Nullable String text,
            int x, int y, int color, boolean shadow,
            int wrapWidth, int spacing
    ) {
        if (text == null || text.isEmpty()) return 0;
        return drawListString(font, TextMeasureUtil.split(font, text, wrapWidth), x, y, color, shadow, spacing);
    }

    /**
     * Draws a list of strings line by line.
     *
     * @param spacing line spacing in pixels.
     * @return the total height rendered in pixels.
     */
    public static int drawListString(
            FontRenderer font, @Nullable List<String> text,
            int x, int y, int color, boolean shadow, int spacing
    ) {
        if (text == null || text.isEmpty()) return 0;

        int currentY = y;
        for (String line : text) {
            if (!line.isEmpty())
                font.drawString(line, x, currentY, color, shadow);
            currentY += font.FONT_HEIGHT + spacing;
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        return currentY - spacing;
    }
}
