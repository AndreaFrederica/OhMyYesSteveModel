package com.elfmcys.ysm.client.gui.button;

import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.network.chat.Component;

/** Named widget for checkbox settings in clean Forge builds. */
public final class CallbackCheckbox extends Checkbox {
    private final Consumer<Boolean> onChange;

    public CallbackCheckbox(int x, int y, int width, int height, Component label,
                            boolean selected, boolean showLabel, Consumer<Boolean> onChange) {
        super(x, y, width, height, label, selected, showLabel);
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    @Override
    public void onPress() {
        super.onPress();
        onChange.accept(selected());
    }
}
