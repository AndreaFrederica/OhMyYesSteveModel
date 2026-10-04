package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.StarModelsCapabilityProvider;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetBatch;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.catalog.client.entry.CatalogModelMetadata;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.util.ModelIdUtil;
import com.elfmcys.ysm.util.RenderUtil;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** A catalog entry that progresses from loading indicator to preview image to a full local renderTarget. */
public final class CatalogModelButton extends Button implements AutoCloseable {
    private static final ResourceLocation ICON = new ResourceLocation(YesSteveModel.MOD_ID, "texture/icon.png");
    private static final int CARD_OVERLAY_Z = 3500;
    private static final int TOOLTIP_Z = 4000;

    private final ClientCatalogEntry entry;
    private final CatalogModelMetadata metadata;
    private final CustomGuiPlayerEntity entity;
    private final boolean needAuth;
    private final SelectionHandler selection;
    private final RenderTargetHandler openRenderTarget;
    private final Component pathName;
    private final CatalogModelCardState state;

    public CatalogModelButton(int x, int y, ClientCatalogEntry entry, boolean needAuth,
                              ClientAssetBatch assets,
                              CustomGuiPlayerEntity entity, SelectionHandler selection,
                              RenderTargetHandler openRenderTarget) {
        super(x, y, 52, 90, name(CatalogModelMetadata.from(entry)), ignored -> { }, DEFAULT_NARRATION);
        this.entry = entry;
        this.metadata = CatalogModelMetadata.from(entry);
        this.entity = entity;
        this.needAuth = needAuth;
        this.selection = selection;
        this.openRenderTarget = openRenderTarget;
        this.pathName = Component.literal(ModelIdUtil.getFileNameFromPath(metadata.path()));
        this.state = new CatalogModelCardState(assets, entry, metadata, entity);
    }

    public Hash256 modelHash() {
        return entry.modelHash();
    }

    public @Nullable ModelRenderTarget renderTarget() {
        return state.renderTarget();
    }

    public void updateDemand(long hoverGeneration, boolean bakeEligible) {
        state.updateDemand(hoverGeneration, bakeEligible);
    }

    @Override
    public Component getMessage() {
        return ClientConfig.SHOW_MODEL_ID_FIRST.get() ? pathName : super.getMessage();
    }

    @Override
    public void onPress() {
        if (!needAuth) {
            selection.select(entry.modelHash(), metadata.path(), metadata.defaultTexture(), state.renderTarget());
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        var renderTarget = state.renderTarget();
        if (button == 1 && isMouseOver(mouseX, mouseY) && renderTarget != null) {
            openRenderTarget.open(entry.modelHash(), metadata.path(), renderTarget);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        state.updatePreviewAnimations(isHovered(), isFocused(), Util.getMillis());
        var color = needAuth ? 0x7F000000 : 0xFF434242;
        graphics.fillGradient(getX(), getY(), getX() + width, getY() + height, color, color);
        var renderTarget = state.renderTarget();
        if (renderTarget != null) {
            renderImage(graphics, state.background());
            if (state.renderDisabled()) {
                renderRenderFailure(graphics);
            } else {
                try {
                    renderEntity(graphics);
                } catch (RuntimeException | LinkageError failure) {
                    // A model-specific GL/material failure must retire this card's
                    // 3D preview instead of bubbling into Minecraft's render loop.
                    state.recordRenderFailure(failure);
                    renderRenderFailure(graphics);
                }
            }
            renderImage(graphics, state.foreground());
        } else {
            var preview = state.preview();
            if (preview != null) {
                renderImage(graphics, preview);
            } else {
                renderLoading(graphics);
            }
        }
        renderName(graphics);
        renderState(graphics);
    }

    public void renderTooltip(GuiGraphics graphics, Screen screen, int mouseX, int mouseY) {
        if (!isHovered()) {
            return;
        }
        var locale = Minecraft.getInstance().getLanguageManager().getSelected();
        var input = CatalogModelTooltipFormatter.input(metadata, entry, locale, state.loadError());
        var lines = CatalogModelTooltipFormatter.format(input, Screen.hasShiftDown(), I18n::get, locale);
        var wrapped = new ArrayList<FormattedCharSequence>();
        for (var line : lines) {
            wrapped.addAll(screen.getMinecraft().font.split(
                    Component.literal(line.text()).withStyle(line.color()),
                    CatalogModelTooltipFormatter.MAX_WIDTH));
        }
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0, 0, TOOLTIP_Z);
            graphics.renderTooltip(screen.getMinecraft().font, wrapped, mouseX, mouseY);
        } finally {
            graphics.pose().popPose();
        }
    }

    @Override
    protected boolean clicked(double mouseX, double mouseY) {
        return !needAuth && super.clicked(mouseX, mouseY);
    }

    @Override
    public void close() {
        state.close();
    }

    private void renderEntity(GuiGraphics graphics) {
        Window window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        RenderSystem.enableScissor((int) (getX() * scale),
                (int) (window.getHeight() - ((getY() + height - 20) * scale)),
                (int) (width * scale), (int) ((height - 20) * scale));
        RenderUtil.renderModelInGui(getX() + width / 2f, getY() + height / 2f + 20f, 30f,
                Minecraft.getInstance().getFrameTime(), entity,
                RegisterEntityRenderersEvent.getPlayerRenderer(),
                metadata.info().getSettings().disablePreviewRotation(), true);
        RenderSystem.disableScissor();
    }

    private void renderImage(GuiGraphics graphics, @Nullable TextureHolder image) {
        if (image == null) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(image.id(), getX(), getY(), 0, 0, width, height, width, height);
        RenderSystem.disableBlend();
    }

    private void renderLoading(GuiGraphics graphics) {
        var phase = (int) ((Util.getMillis() / 250L) % 4L);
        var dots = ".".repeat(phase);
        graphics.drawCenteredString(Minecraft.getInstance().font, dots, getX() + width / 2,
                getY() + (height - 20) / 2, 0xFFF3EFE0);
    }

    private void renderRenderFailure(GuiGraphics graphics) {
        var font = Minecraft.getInstance().font;
        var locale = Minecraft.getInstance().getLanguageManager().getSelected();
        var error = state.loadError();
        var summary = error == null
                ? I18n.get("gui.yes_steve_model.model_preview_failed")
                : CatalogModelTooltipFormatter.errorSummary(error, locale);
        var lines = font.split(Component.literal(summary), width - 6);
        int centerY = getY() + Math.max(8, (height - 20 - Math.min(3, lines.size()) * 9) / 2);
        graphics.drawCenteredString(font,
                Component.translatable("gui.yes_steve_model.model_preview_failed"),
                getX() + width / 2, centerY - 10, 0xFFFF8060);
        for (int index = 0; index < Math.min(3, lines.size()); index++) {
            graphics.drawCenteredString(font, lines.get(index), getX() + width / 2,
                    centerY + index * 9, 0xFFFFC0A0);
        }
    }

    private void renderName(GuiGraphics graphics) {
        Font font = Minecraft.getInstance().font;
        List<FormattedCharSequence> split = font.split(getMessage(), 45);
        if (split.size() > 1) {
            graphics.drawCenteredString(font, split.get(0), getX() + width / 2, getY() + height - 19, 0xF3EFE0);
            graphics.drawCenteredString(font, split.get(1), getX() + width / 2, getY() + height - 10, 0xF3EFE0);
        } else {
            graphics.drawCenteredString(font, getMessage(), getX() + width / 2, getY() + height - 15, 0xF3EFE0);
        }
    }

    private void renderState(GuiGraphics graphics) {
        var z = CARD_OVERLAY_Z;
        if (!needAuth && isHoveredOrFocused()) {
            graphics.fillGradient(getX(), getY() + 1, getX() + 1, getY() + height - 1,
                    z, 0xFFF3EFE0, 0xFFF3EFE0);
            graphics.fillGradient(getX(), getY(), getX() + width, getY() + 1, z, 0xFFF3EFE0, 0xFFF3EFE0);
            graphics.fillGradient(getX() + width - 1, getY() + 1, getX() + width, getY() + height - 1,
                    z, 0xFFF3EFE0, 0xFFF3EFE0);
            graphics.fillGradient(getX(), getY() + height - 1, getX() + width, getY() + height,
                    z, 0xFFF3EFE0, 0xFFF3EFE0);
        }
        if (needAuth) {
            graphics.fillGradient(getX(), getY(), getX() + width, getY() + height,
                    z, 0x9F222222, 0x9F222222);
        }
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP).ifPresent(stars -> {
                if (stars.containModel(entry.modelHash())) {
                    graphics.blit(ICON, getX() + width - 14, getY(), z, 16, 0, 16, 16, 256, 256);
                }
            });
        }
    }

    private static Component name(CatalogModelMetadata metadata) {
        return Component.literal(CatalogModelTooltipFormatter.displayName(metadata,
                Minecraft.getInstance().getLanguageManager().getSelected()));
    }

    @FunctionalInterface
    public interface SelectionHandler {
        void select(Hash256 hash, String path, String texture, @Nullable ModelRenderTarget renderTarget);
    }

    @FunctionalInterface
    public interface RenderTargetHandler {
        void open(Hash256 hash, String path, ModelRenderTarget renderTarget);
    }
}
