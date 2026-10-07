package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import com.elfmcys.ysm.util.InputCheckUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MovementInput;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.input.Keyboard;

import static com.elfmcys.ysm.client.input.AnimationRouletteKey.LOCK_ROULETTE_KEY;

@Mod.EventBusSubscriber(value = Side.CLIENT)
public class PlayerMoveEvent {
    private static boolean LOCK_EXTRA_ANIMATION = false;

    @SubscribeEvent
    public static void onKeyboardInput(InputEvent.KeyInputEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (InputCheckUtil.keyIsPress() && LOCK_ROULETTE_KEY.isActiveAndMatches(Keyboard.getEventKey())) {
            LOCK_EXTRA_ANIMATION = !LOCK_EXTRA_ANIMATION;
        }
    }

    /**
     * 改用 TickEvent.ClientTickEvent 监听按键状态，避免与其他模组（如 Touch Controller） 冲突时漏判按键。
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (LOCK_EXTRA_ANIMATION) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player != null && isMoveKey(player)) {
            var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
            if (cap != null) {
                if (cap.isPlayingExtraAnimation()) {
                    cap.stopExtraAnimation();
                    if (NetworkHandler.isRemoteChannelPresent()) {
                        ClientProtocolGateway.stopSelfAnimation();
                    }
                }
            }
        }
    }

    public static boolean isMoveKey(EntityPlayerSP player) {
        MovementInput input = player.movementInput;
        return input != null && (hasImpulse(input.moveStrafe) || hasImpulse(input.moveForward)
               || input.jump || input.sneak);
    }

    private static boolean hasImpulse(float impulse) {
        return Math.abs(impulse) > 1.0E-5F;
    }

    public static void switchLock() {
        LOCK_EXTRA_ANIMATION = !LOCK_EXTRA_ANIMATION;
    }

    public static boolean isLocked() {
        return LOCK_EXTRA_ANIMATION;
    }
}
