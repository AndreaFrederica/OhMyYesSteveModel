package com.elfmcys.ysm.client.input;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.event.RenderDebugAnimationScreenEvent;
import com.elfmcys.ysm.util.InputCheckUtil;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.input.Keyboard;

@Mod.EventBusSubscriber(value = Side.CLIENT)
public class DebugAnimationKey {
    public static final KeyBinding DEBUG_ANIMATION_KEY = new KeyBinding("key.yes_steve_model.debug_animation.desc",
            KeyConflictContext.IN_GAME, KeyModifier.ALT,
            Keyboard.KEY_B,
            "key.category.yes_steve_model");

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.KeyInputEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (!InputCheckUtil.isInGame()) {
            return;
        }
        if (InputCheckUtil.keyIsPress() && InputCheckUtil.keyIsMatch(DEBUG_ANIMATION_KEY)) {
            if (!RenderDebugAnimationScreenEvent.isEnabled()) {
                RenderDebugAnimationScreenEvent.enable();
            } else {
                RenderDebugAnimationScreenEvent.disable();
            }
        }
    }
}
