package com.elfmcys.ysm.client.compat.aquaacrobatics;

import com.fuzs.aquaacrobatics.entity.Pose;
import com.fuzs.aquaacrobatics.entity.player.IPlayerResizeable;
import net.minecraft.entity.Entity;

final class AACompatInner {
    static boolean isSwimming(Entity entity) {
        return entity instanceof IPlayerResizeable resizeable && resizeable.isSwimming();
    }

    static boolean useAAData(Entity entity) {
        return entity instanceof IPlayerResizeable;
    }

    static boolean isSwimmingPose(Entity entity) {
        return isPose(entity, Pose.SWIMMING);
    }

    static boolean isCrouchingPose(Entity entity) {
        return isPose(entity, Pose.CROUCHING);
    }

    static boolean isFallFlyingPose(Entity entity) {
        return isPose(entity, Pose.FALL_FLYING);
    }

    private static boolean isPose(Entity entity, Pose target) {
        return entity instanceof IPlayerResizeable resizeable && resizeable.getPose() == target;
    }
}
