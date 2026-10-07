package com.elfmcys.ysm.client.compat.touhoulittlemaid.client.animation;

/*
import com.elfmcys.ysm.client.animation.AnimationState;
import com.elfmcys.ysm.client.animation.Priority;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.CustomYsmMaidEntity;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.animation.predicate.YsmMaidMainPredicate;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;

import java.util.function.BiPredicate;

public class GeoMaidAnimatedRegister {
    private static final float MIN_SPEED = 0.05f;

    public static void registerAnimationState() {
        register("death", LoopType.PLAY_ONCE, Priority.HIGHEST, (maid, event) -> maid.isDeadOrDying());
        register("sleep", Priority.HIGHEST, (maid, event) -> maid.getPose() == Pose.SLEEPING);
        register("swim", Priority.HIGHEST, (maid, event) -> maid.isSwimming());

        register("ladder_up", Priority.HIGHEST, (maid, event) -> maid.onClimbable() && getVerticalSpeed(maid) > 0);
        register("ladder_stillness", Priority.HIGHEST, (maid, event) -> maid.onClimbable() && getVerticalSpeed(maid) == 0);
        register("ladder_down", Priority.HIGHEST, (maid, event) -> maid.onClimbable() && getVerticalSpeed(maid) < 0);

        // FIXME：女仆的默认骑乘和待命都是 sit，这是否合适？
        register("sit", Priority.HIGH, (maid, event) -> maid.isMaidInSittingPose());

        register("swim_stand", Priority.NORMAL, (maid, event) -> maid.isInWater() && !maid.onGround());
        register("attacked", LoopType.PLAY_ONCE, Priority.NORMAL, (maid, event) -> maid.hurtTime > 0);
        register("jump", Priority.NORMAL, (maid, event) -> !maid.onGround() && !maid.isInWater());

        register("run", Priority.LOW, (maid, event) -> maid.onGround() && maid.isSprinting());
        register("walk", Priority.LOW, (maid, event) -> maid.onGround() && event.getLimbSwingAmount() > MIN_SPEED);

        register("idle", Priority.LOWEST, (maid, event) -> true);
    }

    private static void register(String animationName, LoopType loopType, int priority, BiPredicate<EntityMaid, AnimationEvent<CustomYsmMaidEntity>> predicate) {
        YsmMaidMainPredicate.register(new AnimationState<>(animationName, loopType, priority, predicate));
    }

    private static void register(String animationName, int priority, BiPredicate<EntityMaid, AnimationEvent<CustomYsmMaidEntity>> predicate) {
        register(animationName, LoopType.LOOP, priority, predicate);
    }

    private static float getVerticalSpeed(LivingEntity livingEntity) {
        return 20 * (float) (livingEntity.position().y - livingEntity.yo);
    }
}
*/
