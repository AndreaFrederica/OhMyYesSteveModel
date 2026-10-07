package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.util.InputCheckUtil;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.input.KeyCodes;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

@Mod.EventBusSubscriber(value = Side.CLIENT, modid = YesSteveModel.MOD_ID)
public class ModInputEvent {
    public static volatile boolean[] KEY_STATES = new boolean[GLFW.GLFW_KEY_LAST + 1];
    public static volatile boolean[] MOUSE_STATES = new boolean[GLFW.GLFW_MOUSE_BUTTON_LAST + 1];

    @SubscribeEvent
    public static void onKeyInput(InputEvent.KeyInputEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (!InputCheckUtil.isInGame()) {
            return;
        }
        int key = KeyCodes.toGlfwKey(Keyboard.getEventKey());
        if (GLFW.GLFW_KEY_SPACE <= key && key <= GLFW.GLFW_KEY_LAST) {
            KEY_STATES[key] = Keyboard.getEventKeyState();
        }
    }

    @SubscribeEvent
    public static void onMouseInput(InputEvent.MouseInputEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (!InputCheckUtil.isInGame()) {
            return;
        }
        int button = Mouse.getEventButton();
        if (GLFW.GLFW_MOUSE_BUTTON_1 <= button && button <= GLFW.GLFW_MOUSE_BUTTON_LAST) {
            MOUSE_STATES[button] = Mouse.getEventButtonState();
        }
    }
}