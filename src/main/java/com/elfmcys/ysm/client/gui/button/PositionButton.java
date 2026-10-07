package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.config.LoadingStateScreenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;

public class PositionButton extends Button {
    public PositionButton(int x, int y) {
        super(x, y, 100, 20, "", b -> {
        });
    }

    @Override
    protected void renderWidget(Minecraft mc, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(mc, mouseX, mouseY, partialTick);

        FontRenderer font = mc.fontRenderer;
        String desc = I18n.format("gui.yes_steve_model.config.loading_state_position");
        font.drawString(desc, this.x + 105, this.y + 6, 0xFFFFFF, false);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public String getMessage() {
        LoadingStateScreenConfig.Position position = LoadingStateScreenConfig.LOADING_STATE_POSITION;
        return position.name();
    }

    @Override
    public void onPress() {
        LoadingStateScreenConfig.Position position = LoadingStateScreenConfig.LOADING_STATE_POSITION;
        LoadingStateScreenConfig.Position nextPosition = switch (position) {
            case TOP_LEFT -> LoadingStateScreenConfig.Position.TOP_CENTER;
            case TOP_CENTER -> LoadingStateScreenConfig.Position.TOP_RIGHT;
            case TOP_RIGHT -> LoadingStateScreenConfig.Position.BOTTOM_RIGHT;
            case BOTTOM_RIGHT -> LoadingStateScreenConfig.Position.BOTTOM_CENTER;
            case BOTTOM_CENTER -> LoadingStateScreenConfig.Position.BOTTOM_LEFT;
            case BOTTOM_LEFT -> LoadingStateScreenConfig.Position.TOP_LEFT;
        };
        LoadingStateScreenConfig.LOADING_STATE_POSITION = nextPosition;
    }
}
