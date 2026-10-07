package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.StarModelsCapabilityProvider;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.ResourceLocation;

public class StarButton extends FlatColorButton {
    private final static ResourceLocation ICON = new ResourceLocation(YesSteveModel.MOD_ID, "texture/icon.png");

    public StarButton(int x, int y) {
        super(x, y, 20, 20, "", (b) -> {
        });
    }

    @Override
    public void renderWidget(Minecraft mc, int mouseX, int mouseY, float pPartialTick) {
        super.renderWidget(mc, mouseX, mouseY, pPartialTick);
        int startX = (this.width - 16) / 2;
        int startY = (this.height - 16) / 2;
        EntityPlayerSP player = mc.player;
        if (player != null) {
            var modelInfoCap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
            if (modelInfoCap == null) return;
            var starModelsCap = player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP, null);
            if (starModelsCap != null) {
                var modelHash = modelInfoCap.getModelHash();
                if (modelHash != null && starModelsCap.containModel(modelHash)) {
                    mc.getTextureManager().bindTexture(ICON);
                    drawScaledCustomSizeModalRect(this.x + startX, this.y + startY, 16, 0, 16, 16, 16, 16, 256, 256);
                } else {
                    mc.getTextureManager().bindTexture(ICON);
                    drawScaledCustomSizeModalRect(this.x + startX, this.y + startY, 0, 0, 16, 16, 16, 16, 256, 256);
                }
            }
        }
    }

    @Override
    public void onPress() {
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player != null) {
            var modelInfoCap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
            if (modelInfoCap == null) return;
            var starModelsCap = player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP, null);
            if (starModelsCap != null) {
                var modelHash = modelInfoCap.getModelHash();
                if (modelHash == null) {
                    return;
                }
                if (starModelsCap.containModel(modelHash)) {
                    starModelsCap.removeModel(modelHash);
                    ClientProtocolGateway.updateStar(modelHash, false);
                } else {
                    starModelsCap.addModel(modelHash);
                    ClientProtocolGateway.updateStar(modelHash, true);
                }
            }
        }
    }
}
