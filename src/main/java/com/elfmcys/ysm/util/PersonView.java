package com.elfmcys.ysm.util;

import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

public final class PersonView {
    public static int getPersonView(IContext<? extends Entity> ctx) {
        // 是客户端玩家，而且不在 GUI 渲染内
        return getPersonView(ctx.entity() == Minecraft.getInstance().player,
                ctx.animationEvent().getRenderContext(), ctx.mc().options.getCameraType());
    }

    static int getPersonView(boolean localPlayer,
                             com.elfmcys.ysm.geckolib3.geo.RenderContext renderContext,
                             CameraType camera) {
        // The external body pass is deliberately mutable and has level=false.
        // It is still a world view for q.is_first_person and ysm.person_view.
        if (localPlayer && (renderContext.level() || renderContext.firstPersonMod())) {
            return camera.ordinal();
        } else {
            // 否则永远返回第三人称正面视角
            return CameraType.THIRD_PERSON_FRONT.ordinal();
        }
    }

    public static boolean isFirstPersonView(AnimatableEntity<?> animatable) {
        Entity entity = animatable.getEntity();
        if (entity == Minecraft.getInstance().player && RenderUtil.isRenderingLevel()) {
            return Minecraft.getInstance().options.getCameraType().ordinal() == CameraType.FIRST_PERSON.ordinal();
        }
        return false;
    }

    public static boolean isInInventory(IContext<? extends Entity> ctx) {
        return isInInventory(ctx.animationEvent());
    }

    public static boolean isInInventory(AnimationEvent<?> event) {
        return event.getAnimatableEntity() instanceof IPreviewEntity || event.getRenderContext().inventory();
    }
}
