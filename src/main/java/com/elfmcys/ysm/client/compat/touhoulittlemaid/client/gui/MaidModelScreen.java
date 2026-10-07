package com.elfmcys.ysm.client.compat.touhoulittlemaid.client.gui;

/*
import com.elfmcys.ysm.client.compat.touhoulittlemaid.capability.YsmMaidCapabilityProvider;
import com.elfmcys.ysm.client.gui.ModelInfoScreen;
import com.elfmcys.ysm.client.gui.PlayerModelScreen;
import com.elfmcys.ysm.client.gui.PlayerTextureScreen;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.util.ModelIdUtil;
import com.elfmcys.ysm.util.NameUtil;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import com.github.tartaricacid.touhoulittlemaid.network.message.YsmMaidModelMessage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.Objects;

public class MaidModelScreen extends PlayerModelScreen {
    private final EntityMaid maid;

    public MaidModelScreen(EntityMaid maid) {
        this.maid = maid;
    }

    @Override
    protected void selectModel(Hash256 hash, String path, String texture, ModelRenderTarget renderTarget) {
        var name = renderTarget == null ? Component.literal(ModelIdUtil.getFileNameFromPath(path))
                : NameUtil.getModeName(renderTarget, path);
        maid.getCapability(YsmMaidCapabilityProvider.CAP).ifPresent(capability ->
                capability.setYsmModel(path, texture));
        NetworkHandler.CHANNEL.sendToServer(new YsmMaidModelMessage(maid.getId(), path, texture, name));
    }

    @Override
    protected PlayerTextureScreen getTextureScreen(PlayerModelScreen parent, Hash256 modelHash,
                                                    ModelRenderTarget model) {
        var maidModel = maid.getCapability(YsmMaidCapabilityProvider.CAP)
                .map(capability -> capability.getModelRenderTarget()).orElse(null);
        return new MaidTextureScreen(parent, modelHash, Objects.requireNonNullElse(maidModel, model), maid);
    }

    @Override
    protected ModelInfoScreen getModelInfoScreen(PlayerModelScreen parent, ModelRenderTarget model) {
        var maidModel = maid.getCapability(YsmMaidCapabilityProvider.CAP)
                .map(capability -> capability.getModelRenderTarget()).orElse(null);
        return new ModelInfoScreen(parent, Objects.requireNonNullElse(maidModel, model));
    }

    @Override
    protected void renderReferenceEntity(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Window window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        RenderSystem.enableScissor((int) ((x + 5) * scale),
                (int) (window.getHeight() - ((y + 200) * scale)),
                (int) (125 * scale), (int) (171 * scale));
        InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x + 67, y + 190, 70,
                x + 67 - mouseX, y + 85 - mouseY, maid);
        RenderSystem.disableScissor();

        maid.getCapability(YsmMaidCapabilityProvider.CAP).ifPresent(capability -> {
            var renderTarget = capability.getModelRenderTarget();
            var path = capability.getModelId();
            var modelName = renderTarget == null ? ModelIdUtil.getFileNameFromPath(path)
                    : renderTarget.getDisplayName(ModelIdUtil.getFileNameFromPath(path));
            var lineY = y + 205;
            for (FormattedCharSequence line : font.split(FormattedText.of(modelName), 125)) {
                graphics.drawString(font, line, x + (135 - font.width(line)) / 2, lineY, 0xF3EFE0);
                lineY += 10;
            }
        });
    }
}
*/
