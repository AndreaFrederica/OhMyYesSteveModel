package com.elfmcys.ysm.client.compat.immersivemelodies;

/*
import com.elfmcys.ysm.client.animation.molang.CtrlBinding;
import com.elfmcys.ysm.client.entity.HumanoidStateTracker;
import immersive_favella.client.animation.EntityModelAnimator;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
*/

/*
public class ImmersiveMelodiesCompatInner {
    static void addInnerBinding(CtrlBinding binding) {
        binding.livingEntityVar("im_pitch", ctx -> {
            if (ctx.animatableEntity().getStateTracker() instanceof HumanoidStateTracker<?> tracker) {
                return tracker.getImmersiveMelodiesData().pitch;
            }
            return 0f;
        });
*/

/*
        binding.livingEntityVar("im_volume", ctx -> {
            if (ctx.animatableEntity().getStateTracker() instanceof HumanoidStateTracker<?> tracker) {
                return tracker.getImmersiveMelodiesData().volume;
            }
            return 0f;
        });
*/

/*
        binding.livingEntityVar("im_current", ctx -> {
            if (ctx.animatableEntity().getStateTracker() instanceof HumanoidStateTracker<?> tracker) {
                return tracker.getImmersiveMelodiesData().current;
            }
            return 0f;
        });
*/

/*
        binding.livingEntityVar("im_delta", ctx -> {
            if (ctx.animatableEntity().getStateTracker() instanceof HumanoidStateTracker<?> tracker) {
                return tracker.getImmersiveMelodiesData().delta;
            }
            return 0L;
        });
*/

/*
        binding.livingEntityVar("im_time", ctx -> {
            if (ctx.animatableEntity().getStateTracker() instanceof HumanoidStateTracker<?> tracker) {
                return tracker.getImmersiveMelodiesData().time;
            }
            return 0L;
        });
    }
*/

/*
    static void updateMelodyProgress(EntityLivingBase entity, ImmersiveMelodiesCompat.ImmersiveMelodiesData imData) {
        Item item = EntityModelAnimator.getInstrument(entity);
        if (item != null) {
            float time = (Minecraft.getMinecraft().isGamePaused() ? 0.0F : Minecraft.getInstance().getFrameTime()) + (float) entity.tickCount;
            MelodyProgress progress = MelodyProgressManager.INSTANCE.getProgress(entity);
            progress.visualTick(time);
            imData.pitch = progress.getCurrentPitch();
            imData.volume = progress.getCurrentVolume();
            imData.current = progress.getCurrent();
            imData.delta = progress.delta();
            imData.time = progress.getTime();
        }
    }
}
*/
