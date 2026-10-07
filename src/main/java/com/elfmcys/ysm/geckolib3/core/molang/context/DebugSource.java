package com.elfmcys.ysm.geckolib3.core.molang.context;

import net.minecraft.util.text.ITextComponent;

public interface DebugSource {
    void print(String message, Object...args);

    void print(ITextComponent message);
}
