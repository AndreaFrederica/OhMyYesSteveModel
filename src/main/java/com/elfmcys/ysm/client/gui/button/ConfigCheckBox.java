package com.elfmcys.ysm.client.gui.button;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

public class ConfigCheckBox extends Checkbox {
    public ConfigCheckBox(int pX, int pY, String key, @NotNull FontRenderer font, boolean current, Consumer<Boolean> setter) {
        super(pX, pY, I18n.format("gui.yes_steve_model.config." + key), font, current, (button -> {
            setter.accept(((Checkbox) button).selected());
        }));
    }
}
