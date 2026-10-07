package com.elfmcys.ysm.util;

import net.minecraft.util.text.*;
import net.minecraft.util.text.event.ClickEvent;

public final class ComponentUtils {
    /**
     * Wraps the text with square brackets.
     */
    public static ITextComponent wrapInSquareBrackets(ITextComponent toWrap) {
        return new TextComponentTranslation("message.yes_steve_model.model.square_brackets", toWrap);
    }

    public static ITextComponent suggestOnClickText(String text) {
        Style style = new Style().setColor(TextFormatting.GREEN).setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, text));
        return wrapInSquareBrackets(new TextComponentString(text).setStyle(style));
    }
}
