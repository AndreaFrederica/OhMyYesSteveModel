package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.StarModelsCapabilityProvider;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.client.gui.Screen;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetBatch;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.catalog.client.entry.CatalogModelMetadata;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.util.GuiRenderUtil;
import com.elfmcys.ysm.util.ModelIdUtil;
import com.elfmcys.ysm.util.RenderUtil;
import com.elfmcys.ysm.util.TextMeasureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/** A catalog entry that progresses from loading indicator to preview image to a full local renderTarget. */
public final class CatalogModelButton extends Button implements AutoCloseable {
    private static final ResourceLocation ICON = new ResourceLocation(YesSteveModel.MOD_ID, "texture/icon.png");

    private final ClientCatalogEntry entry;
    private final CatalogModelMetadata metadata;
    private final CustomGuiPlayerEntity entity;
    private final boolean needAuth;
    private final SelectionHandler selection;
    private final RenderTargetHandler openRenderTarget;
    private final String pathName;
    private final CatalogModelCardState state;

    private boolean focused;

    public CatalogModelButton(int x, int y, ClientCatalogEntry entry, boolean needAuth,
                              ClientAssetBatch assets,
                              CustomGuiPlayerEntity entity, SelectionHandler selection,
                              RenderTargetHandler openRenderTarget) {
        super(x, y, 52, 90, name(CatalogModelMetadata.from(entry)), ignored -> { });
        this.entry = entry;
        this.metadata = CatalogModelMetadata.from(entry);
        this.entity = entity;
        this.needAuth = needAuth;
        this.selection = selection;
        this.openRenderTarget = openRenderTarget;
        this.pathName = ModelIdUtil.getFileNameFromPath(metadata.path());
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
    public String getMessage() {
        return ClientConfig.SHOW_MODEL_ID_FIRST ? pathName : super.getMessage();
    }

    @Override
    public void onPress() {
        if (!needAuth) {
            selection.select(entry.modelHash(), metadata.path(), metadata.defaultTexture(), state.renderTarget());
        }
    }

    @Deprecated
    @Override
    public boolean mousePressed(@NotNull Minecraft mc, int mouseX, int mouseY) {
        return false;
    }

    public boolean mouseClicked(Minecraft mc, int mouseX, int mouseY, int button) {
        boolean pressed = !needAuth && super.mousePressed(mc, mouseX, mouseY);

        var renderTarget = state.renderTarget();
        if (button == 1 && pressed && renderTarget != null) {
            openRenderTarget.open(entry.modelHash(), metadata.path(), renderTarget);
        }

        return pressed;
    }

    @Override
    public void renderWidget(Minecraft mc, int mouseX, int mouseY, float partialTick) {
        state.updatePreviewAnimations(isMouseOver(), focused, Minecraft.getSystemTime());
        var color = needAuth ? 0x7F000000 : 0xFF434242;
        this.drawGradientRect(x, y, x + width, y + height, color, color);
        var renderTarget = state.renderTarget();
        if (renderTarget != null) {
            renderImage(mc, state.background());
            renderEntity(partialTick);
            renderImage(mc, state.foreground());
        } else {
            var preview = state.preview();
            if (preview != null) {
                renderImage(mc, preview);
            } else {
                renderLoading(mc);
            }
        }
        renderName(mc);
        renderState(mc);
    }

    public void renderTooltip(GuiScreen screen, int mouseX, int mouseY) {
        if (!isMouseOver()) {
            return;
        }
        var locale = Minecraft.getMinecraft().getLanguageManager().getCurrentLanguage().getLanguageCode();
        var input = CatalogModelTooltipFormatter.input(metadata, entry, locale, state.loadError());
        var lines = CatalogModelTooltipFormatter.format(input, GuiScreen.isShiftKeyDown(), I18n::format);
        var wrapped = new ArrayList<String>();
        for (var line : lines) {
            wrapped.addAll(TextMeasureUtil.split(screen.mc.fontRenderer,
                    line.color() + line.text(),
                    CatalogModelTooltipFormatter.MAX_WIDTH));
        }
        screen.drawHoveringText(wrapped, mouseX, mouseY);
    }

    @Override
    public void close() {
        state.close();
    }

    private void renderEntity(float partialTick) {
        GuiRenderUtil.enableScissor(x, y, width, height - 20);
        RenderUtil.renderModelInGui(x + width / 2f, y + height / 2f + 20f, 30f,
                partialTick, entity,
                RegisterEntityRenderersEvent.getPlayerRenderer(),
                metadata.info().getSettings().disablePreviewRotation(), true);
        GuiRenderUtil.disableScissor();
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
    }

    private void renderImage(Minecraft mc, @Nullable TextureHolder image) {
        if (image == null) {
            return;
        }
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        mc.getTextureManager().bindTexture(image.id());
        drawModalRectWithCustomSizedTexture(x, y, 0, 0, width, height, width, height);
        GlStateManager.disableBlend();
    }

    private void renderLoading(Minecraft mc) {
        var phase = (int) ((Minecraft.getSystemTime() / 250L) % 4L);
        var dots = ".".repeat(phase);
        this.drawCenteredString(mc.fontRenderer, dots, x + width / 2,
                y + (height - 20) / 2, 0xFFF3EFE0);
    }

    private void renderName(Minecraft mc) {
        FontRenderer font = mc.fontRenderer;
        List<String> split = TextMeasureUtil.split(font, getMessage(), 45);
        if (split.size() > 1) {
            this.drawCenteredString(font, split.get(0), x + width / 2, y + height - 19, 0xF3EFE0);
            this.drawCenteredString(font, split.get(1), x + width / 2, y + height - 10, 0xF3EFE0);
        } else {
            this.drawCenteredString(font, getMessage(), x + width / 2, y + height - 15, 0xF3EFE0);
        }
    }

    private void renderState(Minecraft mc) {
        if (!needAuth && isMouseOver()) {
            this.drawGradientRect(x, y + 1, x + 1, y + height - 1,
                    0xFFF3EFE0, 0xFFF3EFE0);
            this.drawGradientRect(x, y, x + width, y + 1, 0xFFF3EFE0, 0xFFF3EFE0);
            this.drawGradientRect(x + width - 1, y + 1, x + width, y + height - 1,
                    0xFFF3EFE0, 0xFFF3EFE0);
            this.drawGradientRect(x, y + height - 1, x + width, y + height,
                    0xFFF3EFE0, 0xFFF3EFE0);
        }
        if (needAuth) {
            this.drawGradientRect(x, y, x + width, y + height,
                    0x9F222222, 0x9F222222);
        }
        var player = mc.player;
        if (player != null) {
            var stars = player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP, null);
            if (stars != null) {
                if (stars.containModel(entry.modelHash())) {
                    mc.getTextureManager().bindTexture(ICON);
                    drawModalRectWithCustomSizedTexture(x + width - 14, y, 16, 0, 16, 16, 256, 256);
                }
            }
        }
    }

    private static String name(CatalogModelMetadata metadata) {
        return CatalogModelTooltipFormatter.displayName(metadata,
                Minecraft.getMinecraft().getLanguageManager().getCurrentLanguage().getLanguageCode());
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    public boolean isMouseOver(int mouseX, int mouseY) {
        return Screen.isMouseWithin(mouseX, mouseY, x, y, width, height);
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
