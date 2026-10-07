package com.elfmcys.ysm.client.input;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.elfmcys.ysm.client.gui.AnimationRouletteScreen;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.util.InputCheckUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.input.Keyboard;

@Mod.EventBusSubscriber(value = Side.CLIENT)
public class AnimationRouletteKey {
    public static final KeyBinding ANIMATION_ROULETTE_KEY = new KeyBinding("key.yes_steve_model.animation_roulette.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            Keyboard.KEY_Z,
            "key.category.yes_steve_model");

    public static final KeyBinding LOCK_ROULETTE_KEY = new KeyBinding("key.yes_steve_model.lock_roulette.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.ALT,
            Keyboard.KEY_L,
            "key.category.yes_steve_model");

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.KeyInputEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (!InputCheckUtil.isInGame()) {
            return;
        }
        if (InputCheckUtil.keyIsPress() && InputCheckUtil.keyIsMatch(ANIMATION_ROULETTE_KEY)
            && (!NetworkHandler.isRemoteChannelPresent() || ServerConfig.CAN_SWITCH_MODEL)) {
            if (TlmClientCompat.pointToMaid()) {
                TlmClientCompat.onRouletteMainKeyPressed();
            } else if (Minecraft.getMinecraft().player != null) {
                var cap = Minecraft.getMinecraft().player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
                if (cap != null) {
                    String modelId = cap.getModelId();
                    var model = cap.getModelRenderTarget();
                    if (model != null && !model.info().getExtraAnimations().isEmpty()) {
                        if (Minecraft.getMinecraft().currentScreen == null) {
                            Minecraft.getMinecraft().displayGuiScreen(new AnimationRouletteScreen(modelId, model, cap));
                            return;
                        }
                        if (Minecraft.getMinecraft().currentScreen instanceof AnimationRouletteScreen) {
                            Minecraft.getMinecraft().displayGuiScreen(null);
                        }
                    }
                };
            }
        }
    }
}
