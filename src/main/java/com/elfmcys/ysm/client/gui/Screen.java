package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.gui.button.Button;
import com.elfmcys.ysm.mixin.client.GuiScreenAccessor;
import com.elfmcys.ysm.util.TextMeasureUtil;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.List;

// TODO：优化拖拽效果
public class Screen extends GuiScreen {
    @Override
    protected void actionPerformed(@NotNull GuiButton guiButton) throws IOException {
        if (guiButton instanceof Button button) {
            button.onPress();
            return;
        }
        super.actionPerformed(guiButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        super.drawScreen(mouseX, mouseY, partialTick);
        renderTooltip(this, mouseX, mouseY);
    }

    protected static final int LEFT_MOUSE_BUTTON = 0;
    protected static final int RIGHT_MOUSE_BUTTON = 1;
    private int lastMouseX;
    private int laseMouseY;

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        this.updateMousePos(mouseX, mouseY, button);
        super.mouseClicked(mouseX, mouseY, button);
    }

    protected void updateMousePos(int mouseX, int mouseY, int button) {
        if (button == LEFT_MOUSE_BUTTON || button == RIGHT_MOUSE_BUTTON) {
            this.lastMouseX = mouseX;
            this.laseMouseY = mouseY;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        this.mouseDragged(mouseX, mouseY, button, mouseX - this.lastMouseX, mouseY - this.laseMouseY);
        this.lastMouseX = mouseX;
        this.laseMouseY = mouseY;
    }

    protected void mouseDragged(int mouseX, int mouseY, int button, int deltaX, int deltaY) {
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            this.mouseScrolled(
                    Mouse.getEventX() * this.width / this.mc.displayWidth,
                    this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1,
                    dWheel > 0 ? 1 : -1
            );
        }
    }

    protected void mouseScrolled(int mouseX, int mouseY, int delta) {
    }

    /**
     * 清除 {@link #buttonList} 并发起 Forge 事件，供 Gui 自己调用。其实就是把 {@link #initGui()} 包装了一下。
     */
    protected void refreshGui() {
        if (!MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.InitGuiEvent.Pre(this, this.buttonList))) {
            this.buttonList.clear();
            this.initGui();
        }
        MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.InitGuiEvent.Post(this, this.buttonList));
    }

    @Override
    public void drawCenteredString(@NotNull FontRenderer font, @NotNull String text, int x, int y, int color) {
        super.drawCenteredString(font, text, x, y, color);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void drawString(@NotNull FontRenderer font, @NotNull String text, int x, int y, int color) {
        super.drawString(font, text, x, y, color);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static boolean isMouseWithin(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public static void renderTooltip(GuiScreen screen, int mouseX, int mouseY) {
        List<GuiButton> buttonList = ((GuiScreenAccessor) screen).getButtonList();
        if (buttonList == null) {
            return;
        }
        FontRenderer font = ((GuiScreenAccessor) screen).getFontRenderer();
        for (var guiButton : buttonList) {
            if (guiButton instanceof Button button && button.id == -1 && button.isMouseOver()) {
                var tooltip = button.getTooltip();
                if (tooltip == null) continue;
                var tooltipPos = TextMeasureUtil.positionMenuTooltip(
                        font, tooltip, screen.width, screen.height,
                        mouseX, mouseY, button.y, button.height);
                tooltipPos = TextMeasureUtil.applyDrawingOffset(tooltipPos);
                screen.drawHoveringText(tooltip, tooltipPos.x(), tooltipPos.y());
            }
        }
    }
}
