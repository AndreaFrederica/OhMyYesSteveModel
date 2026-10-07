package com.elfmcys.ysm.client.animation.molang.functions;

import com.elfmcys.ysm.client.event.ModInputEvent;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import com.elfmcys.ysm.molang.runtime.Function;
import com.elfmcys.ysm.util.InputCheckUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

// TODO
public class InputCheck {
    public static class Keyboard implements Function {
        @Override
        public @Nullable Object evaluate(@NotNull ExecutionContext<?> context, @NotNull ArgumentCollection arguments) {
            if (!InputCheckUtil.isInGame()) {
                return false;
            }
            for (var i = 0; i < arguments.size(); i++) {
                int keyCode = arguments.getAsInt(context, i);
                if (GLFW.GLFW_KEY_SPACE <= keyCode && keyCode <= GLFW.GLFW_KEY_LAST && ModInputEvent.KEY_STATES[keyCode]) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean validateArgumentSize(int size) {
            return size >= 1;
        }
    }

    public static class Mouse implements Function {
        @Override
        public @Nullable Object evaluate(@NotNull ExecutionContext<?> context, @NotNull ArgumentCollection arguments) {
            if (!InputCheckUtil.isInGame()) {
                return false;
            }
            int button = arguments.getAsInt(context, 0);
            if (GLFW.GLFW_MOUSE_BUTTON_1 <= button && button <= GLFW.GLFW_MOUSE_BUTTON_LAST) {
                return ModInputEvent.MOUSE_STATES[button];
            }
            return false;
        }

        @Override
        public boolean validateArgumentSize(int size) {
            return size == 1;
        }
    }
}
