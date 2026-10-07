package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.gui.button.Button;
import com.elfmcys.ysm.client.gui.button.Checkbox;
import com.elfmcys.ysm.config.Config;
import com.elfmcys.ysm.config.ExtraPlayerScreenConfig;
import com.elfmcys.ysm.util.RenderUtil;
import com.elfmcys.ysm.util.TextMeasureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;

public class ExtraPlayerConfigScreen extends Screen {
    private static final int RESET_KEY = Keyboard.KEY_R;
    private int posX;
    private int posY;
    private float scale;
    private float yawOffset;
    private boolean isChangePos = false;
    private boolean isChangeScale = false;
    private int controlSize = 5;
    private int yawChangeButton = RIGHT_MOUSE_BUTTON;

    public ExtraPlayerConfigScreen() {
        super();
        this.posX = ExtraPlayerScreenConfig.PLAYER_POS_X;
        this.posY = ExtraPlayerScreenConfig.PLAYER_POS_Y;
        this.scale = ExtraPlayerScreenConfig.PLAYER_SCALE;
        this.yawOffset = ExtraPlayerScreenConfig.PLAYER_YAW_OFFSET;
        if (AndroidCompat.isAndroid()) {
            this.controlSize = 16;
            this.yawChangeButton = LEFT_MOUSE_BUTTON;
        }
    }

    @Override
    public void initGui() {
        int yOffset = -30;
        if (AndroidCompat.isAndroid()) {
            String reset = I18n.format("controls.reset");
            this.addButton(new Button(this.width / 2 - 50, this.height - 35, 100, 30, reset, button -> this.reset()));
            yOffset = -60;
        }

        String name = I18n.format("gui.yes_steve_model.hide_or_show");
        int nameWidth = this.fontRenderer.getStringWidth(name) + 24;
        this.addButton(new Checkbox((this.width - nameWidth) / 2, this.height + yOffset, name, this.fontRenderer,
                ExtraPlayerScreenConfig.DISABLE_PLAYER_RENDER,
                b -> ExtraPlayerScreenConfig.DISABLE_PLAYER_RENDER = ((Checkbox) b).selected()));
    }

    @Override
    public void drawScreen(int pMouseX, int pMouseY, float frameDeltaTime) {
        int startX = this.posX;
        int startY = this.posY;
        int endX = (int) (startX + this.scale * 1);
        int endY = (int) (startY + this.scale * 2);

        GlStateManager.pushMatrix();
        GlStateManager.translate(0, 0, -500 - (50 * this.scale / 40));

        this.drawVerticalLine(width / 2 - 1, -2, height + 2, 0x9fffffff);
        this.drawHorizontalLine(-2, width + 2, height / 2 - 1, 0x9fffffff);

        this.drawVerticalLine(10, -2, height + 2, 0x9fffffff);
        this.drawVerticalLine(width - 10, -2, height + 2, 0x9fffffff);
        this.drawHorizontalLine(-2, width + 2, 10, 0x9fffffff);
        this.drawHorizontalLine(-2, width + 2, height - 10, 0x9fffffff);

        this.drawVerticalLine(startX, startY, endY, 0xffff0000);
        this.drawVerticalLine(endX, startY, endY, 0xffff0000);
        this.drawHorizontalLine(startX, endX, startY, 0xffff0000);
        this.drawHorizontalLine(startX, endX, endY, 0xffff0000);

        this.drawGradientRect(startX, startY, endX, endY, 0x4fffffff, 0x4fffffff);

        this.drawGradientRect(startX - this.controlSize, startY - this.controlSize, startX + this.controlSize, startY + this.controlSize, 0xFF00FF9F, 0xFF00FF9F);
        this.drawGradientRect(endX - this.controlSize, endY - this.controlSize, endX + this.controlSize, endY + this.controlSize, 0xFF00009F, 0xFF00009F);

        int y = 15;
        String component = I18n.format("gui.yes_steve_model.extra_player_render.tips");
        List<String> split = TextMeasureUtil.split(this.fontRenderer, component, 500);
        for (String charSequence : split) {
            int w = fontRenderer.getStringWidth(charSequence);
            this.drawString(fontRenderer, charSequence, width - 15 - w, y, 0xFFFFFF);
            y += 10;
        }

        GlStateManager.popMatrix();

        if (mc.player != null && !ExtraPlayerScreenConfig.DISABLE_PLAYER_RENDER) {
            RenderUtil.renderExtraPlayerEntity(mc.player, this.posX, this.posY, this.scale, this.yawOffset, -500, frameDeltaTime);
        }

        super.drawScreen(pMouseX, pMouseY, frameDeltaTime);
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        boolean xIn = this.posX - this.controlSize < mouseX && mouseX < this.posX + this.controlSize;
        boolean yIn = this.posY - this.controlSize < mouseY && mouseY < this.posY + this.controlSize;
        if (button == LEFT_MOUSE_BUTTON && xIn && yIn) {
            this.isChangePos = true;
        }
        int endX = (int) (this.posX + this.scale * 1);
        int endY = (int) (this.posY + this.scale * 2);
        boolean xIn2 = endX - this.controlSize < mouseX && mouseX < endX + this.controlSize;
        boolean yIn2 = endY - this.controlSize < mouseY && mouseY < endY + this.controlSize;
        if (button == LEFT_MOUSE_BUTTON && xIn2 && yIn2) {
            this.isChangeScale = true;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int button) {
        this.isChangePos = false;
        this.isChangeScale = false;
        super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void mouseDragged(int mouseX, int mouseY, int button, int deltaX, int deltaY) {
        if (isChangeScale) {
            double scale1 = mouseX - this.posX;
            double scale2 = (double) (mouseY - this.posY) / 2;
            this.scale = (float) Math.min(scale1, scale2);
            return;
        }
        if (isChangePos) {
            this.posX = mouseX;
            this.posY = mouseY;
            return;
        }
        // 手机下是左键，桌面端是右键
        if (button == this.yawChangeButton) {
            this.yawOffset += (float) (deltaX * 2);
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == RESET_KEY && isAltKeyDown()) {
            this.reset();
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void reset() {
        this.posX = 10;
        this.posY = 10;
        this.scale = 40;
        this.yawOffset = 5;
    }

    @Override
    public void onGuiClosed() {
        ExtraPlayerScreenConfig.PLAYER_POS_X = this.posX;
        ExtraPlayerScreenConfig.PLAYER_POS_Y = this.posY;
        ExtraPlayerScreenConfig.PLAYER_SCALE = this.scale;
        ExtraPlayerScreenConfig.PLAYER_YAW_OFFSET = this.yawOffset;
        Config.save();
        super.onGuiClosed();
    }

    @Override
    public void onResize(@NotNull Minecraft mc, int width, int height) {
        ExtraPlayerScreenConfig.PLAYER_POS_X = this.posX;
        ExtraPlayerScreenConfig.PLAYER_POS_Y = this.posY;
        ExtraPlayerScreenConfig.PLAYER_SCALE = this.scale;
        ExtraPlayerScreenConfig.PLAYER_YAW_OFFSET = this.yawOffset;
        Config.save();
        super.onResize(mc, width, height);
    }
}
