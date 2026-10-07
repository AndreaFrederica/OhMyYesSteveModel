package com.elfmcys.ysm.client.compat.touhoulittlemaid.client.input;

/*
import com.elfmcys.ysm.client.compat.touhoulittlemaid.capability.YsmMaidCapabilityProvider;
import com.elfmcys.ysm.client.gui.AnimationRouletteScreen;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.EntityHitResult;

public class OpenRouletteScreen {
    public static boolean pointToMaid() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        if (!(Minecraft.getInstance().hitResult instanceof EntityHitResult result)) {
            return false;
        }
        if (result.getEntity() instanceof EntityMaid maid) {
            if (!maid.isYsmModel()) {
                return false;
            }
            return player.getUUID().equals(maid.getOwnerUUID());
        }
        return false;
    }

    public static void onRouletteMainKeyPressed() {
        if (!(Minecraft.getInstance().hitResult instanceof EntityHitResult result)) {
            return;
        }
        if (result.getEntity() instanceof EntityMaid maid) {
            maid.getCapability(YsmMaidCapabilityProvider.CAP).ifPresent(cap -> {
                var model = cap.getModelRenderTarget();
                if (model != null && !model.info().getExtraAnimations().isEmpty()) {
                    if (Minecraft.getInstance().screen == null) {
                        Minecraft.getInstance().setScreen(new AnimationRouletteScreen(cap.getModelId(), model, cap));
                        return;
                    }
                    if (Minecraft.getInstance().screen instanceof AnimationRouletteScreen) {
                        Minecraft.getInstance().setScreen(null);
                    }
                }
            });
        }
    }
}
*/
