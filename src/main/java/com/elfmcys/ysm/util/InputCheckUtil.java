package com.elfmcys.ysm.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.settings.KeyModifier;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class InputCheckUtil {
    public static boolean keyIsPress() {
        return Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent();
    }

    public static boolean keyIsMatch(KeyBinding keyMapping) {
        int keyCode = Keyboard.getEventKey();
        return matches(keyMapping, keyCode)
               && keyMapping.getKeyModifier().equals(KeyModifier.getActiveModifier());
    }

    public static boolean matches(KeyBinding keyMapping, int targetKeyCode) {
        int keyCode = keyMapping.getKeyCode();
        return keyCode != 0 && keyCode == targetKeyCode;
    }

    public static boolean isInGame() {
        Minecraft mc = Minecraft.getMinecraft();
        // 不能打开任何 GUI
        if (mc.currentScreen != null) {
            return false;
        }
        // 当前窗口捕获鼠标操作
        if (!Mouse.isGrabbed()) {
            return false;
        }
        // 选择了当前窗口
        return mc.inGameHasFocus;
    }
}
