package com.elfmcys.ysm.client.animation.debug;

import com.elfmcys.ysm.geckolib3.core.molang.context.DebugSource;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;

public class CustomDebugSource implements DebugSource {
    public static final CustomDebugSource INSTANCE = new CustomDebugSource();

    private CustomDebugSource() {
    }

    @Override
    public void print(String message, Object...args) {
        print(new TextComponentString(String.format(message, args)));
    }

    @Override
    public void print(ITextComponent message) {
        if(Minecraft.getMinecraft().player == null) {
            return;
        }
        Minecraft.getMinecraft().addScheduledTask(() -> {
            Minecraft.getMinecraft().player.sendMessage(new TextComponentTranslation("message.yes_steve_model.model.debug_animation.output")
                            .appendSibling(message));
        });
    }
}
