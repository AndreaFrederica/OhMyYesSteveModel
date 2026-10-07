package com.elfmcys.ysm.util;

import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

public final class PersonView {
    public static final int FIRST_PERSON = 0;
    public static final int THIRD_PERSON_BACK = 1;
    public static final int THIRD_PERSON_FRONT = 2;

    public static int getPersonView(IContext<? extends Entity> ctx) {
        // 是客户端玩家，而且不在 GUI 渲染内
        var renderContext = ctx.animationEvent().getRenderContext();
        if (ctx.entity() == Minecraft.getMinecraft().player && renderContext.level()) {
            return ctx.mc().gameSettings.thirdPersonView;
        } else {
            // 否则永远返回第三人称正面视角
            return THIRD_PERSON_FRONT;
        }
    }

    public static boolean isFirstPersonView(AnimatableEntity<?> animatable) {
        Entity entity = animatable.getEntity();
        if (entity == Minecraft.getMinecraft().player && RenderUtil.isRenderingLevel()) {
            return Minecraft.getMinecraft().gameSettings.thirdPersonView == FIRST_PERSON;
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
