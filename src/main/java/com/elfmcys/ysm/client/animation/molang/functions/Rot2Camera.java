package com.elfmcys.ysm.client.animation.molang.functions;

import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.function.ContextFunction;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import com.elfmcys.ysm.util.PersonView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;

public class Rot2Camera extends ContextFunction<Object> {
    @Override
    protected Object eval(ExecutionContext<IContext<Object>> ctx, ArgumentCollection arguments) {
        int axis = arguments.getAsInt(ctx, 0);
        if (axis < 0 || axis > 1) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        RenderManager renderManager = mc.getRenderManager();
        if (axis == 0) {
            float xRot = mc.gameSettings.thirdPersonView == PersonView.THIRD_PERSON_FRONT ? -renderManager.playerViewX : renderManager.playerViewX;
            return -xRot;
        } else {
            return 180 + renderManager.playerViewY;
        }
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
