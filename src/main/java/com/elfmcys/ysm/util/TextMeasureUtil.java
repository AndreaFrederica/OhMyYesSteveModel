package com.elfmcys.ysm.util;

import com.elfmcys.ysm.mclib.utils.Interpolations;
import net.minecraft.client.gui.FontRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.commons.lang3.StringUtils;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SuppressWarnings({"unused", "UnusedReturnValue"})
@SideOnly(Side.CLIENT)
public final class TextMeasureUtil {
    /**
     * Splits and wraps text into multiple lines, converting both {@code \n} and {@code \\n} to line breaks.
     * <p>
     * An optimized version of {@link FontRenderer#listFormattedStringToWidth(String, int)} that handles manual line breaks.
     */
    public static List<String> split(FontRenderer font, String text, int wrapWidth) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        final List<String> result = new ArrayList<>();
        for (String para : applyLineBreaks(text)) {
            if (para.isEmpty()) {
                result.add(StringUtils.EMPTY);
                continue;
            }
            result.addAll(font.listFormattedStringToWidth(para, wrapWidth));
        }
        return result;
    }

    /**
     * Splits text into multiple lines, converting both {@code \n} and {@code \\n} to line breaks.
     */
    public static List<String> applyLineBreaks(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        return List.of(text.replace("\\n", "\n").split("\n", -1));
    }

    /**
     * @return the width of the longest line in the list.
     */
    public static int getListStringWidth(FontRenderer font, List<String> text) {
        int width = 0;
        if (text == null || text.isEmpty()) return width;
        for (String line : text) {
            width = Math.max(width, font.getStringWidth(line));
        }
        return width;
    }

    /**
     * @param spacing line spacing in pixels.
     * @return the height of all the lines in the list.
     */
    public static int getListStringHeight(FontRenderer font, List<String> text, int spacing) {
        if (text == null) return 0;
        return getListStringHeight(font, text.size(), spacing);
    }

    /**
     * @param spacing line spacing in pixels.
     * @return the height of all the lines in the list.
     */
    public static int getListStringHeight(FontRenderer font, int lineCount, int spacing) {
        if (lineCount == 0) return 0;
        return (font.FONT_HEIGHT + spacing) * lineCount - spacing;
    }

    public static Vector2ic positionMenuTooltip(
            FontRenderer font, List<String> tooltip, int screenWidth, int screenHeight,
            int mouseX, int mouseY, int widgetY, int widgetHeight) {
        return positionMenuTooltip(screenWidth, screenHeight, mouseX, mouseY,
                getListStringWidth(font, tooltip), getTooltipHeight(tooltip), widgetY, widgetHeight);
    }

    private static Vector2ic positionMenuTooltip(
            int screenWidth, int screenHeight, int mouseX, int mouseY,
            int tooltipWidth, int tooltipHeight, int widgetY, int widgetHeight) {
        // Logic from MenuTooltipPositioner
        Vector2i tooltipPos = new Vector2i(mouseX + 12, mouseY);
        if (tooltipPos.x + tooltipWidth > screenWidth - 5) {
            tooltipPos.x = Math.max(mouseX - 12 - tooltipWidth, 9);
        }

        tooltipPos.y += 3;
        int framedTooltipHeight = tooltipHeight + 3 + 3;
        int preferredBelowY = widgetY + widgetHeight + 3 + getOffset(0, 0, widgetHeight);
        int bottomLimit = screenHeight - 5;
        if (preferredBelowY + framedTooltipHeight <= bottomLimit) {
            tooltipPos.y += getOffset(tooltipPos.y, widgetY, widgetHeight);
        } else {
            tooltipPos.y -= framedTooltipHeight + getOffset(tooltipPos.y, widgetY + widgetHeight, widgetHeight);
        }

        return tooltipPos;
    }

    public static Vector2ic applyDrawingOffset(Vector2ic tooltipPos) {
        return new Vector2i(tooltipPos.x() - 12, tooltipPos.y() + 12);
    }

    private static int getOffset(int mouseY, int widgetY, int widgetHeight) {
        int distance = Math.min(Math.abs(mouseY - widgetY), widgetHeight);
        return Math.round(Interpolations.lerp(widgetHeight - 3, 5.0F, (float) distance / (float) widgetHeight));
    }

    private static int getTooltipHeight(List<String> tooltips) {
        return getTooltipHeight(tooltips.size());
    }

    private static int getTooltipHeight(int lineCount) {
        return lineCount <= 1 ? 8 : 8 + (lineCount - 1) * 10 + 2;
    }
}
