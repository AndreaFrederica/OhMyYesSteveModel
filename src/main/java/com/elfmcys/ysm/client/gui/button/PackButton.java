package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetBatch;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.catalog.client.ModelPackInfo;
import com.elfmcys.ysm.client.texture.CustomTexture;
import com.elfmcys.ysm.client.texture.CustomTextureManager;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.util.TextMeasureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public class PackButton extends Button implements AutoCloseable {
    private final static ResourceLocation ICON = new ResourceLocation(YesSteveModel.MOD_ID, "texture/default_pack_icon.png");

    private final ModelPackInfo pack;
    private @Nullable CustomTexture texture;
    private TextureHolder icon;
    private boolean closed;

    public PackButton(int x, int y, int width, int height, ModelPackInfo pack,
                      @Nullable ModelPackDescriptor descriptor, ClientAssetBatch assets, Consumer<Button> onPress) {
        super(x, y, width, height, LanguageManager.getI18n(pack, "name", pack.name()), onPress);
        this.pack = pack;
        this.icon = pack.icon() == null ? null : CustomTextureManager.register(pack.icon(), 10 * 20);
        if (descriptor != null && descriptor.coverHash() != null) {
            assets.packCover(descriptor)
                    .whenComplete((source, error) -> Minecraft.getMinecraft().addScheduledTask(() -> {
                        if (closed) {
                            return;
                        }
                        if (source != null) {
                            texture = ClientModelService.instance().createTexture(source);
                            icon = CustomTextureManager.register(texture, 10 * 20);
                        } else if (!isCancellation(error)) {
                            if (error == null) {
                                YesSteveModel.LOGGER.debug(
                                        "Failed to load model pack cover {}: unknown error",
                                        descriptor.hierarchy());
                            } else {
                                YesSteveModel.LOGGER.debug("Failed to load model pack cover {}",
                                        descriptor.hierarchy(), unwrap(error));
                            }
                        }
                    }));
        }
    }

    @Override
    public void renderWidget(Minecraft mc, int mouseX, int mouseY, float frameDeltaTime) {
        if (icon == null && pack.icon() != null) {
            icon = CustomTextureManager.register(pack.icon(), 10 * 20);
        }
        FontRenderer font = mc.fontRenderer;

        int backgroundColor = 0xFF_9B51E0;
        this.drawGradientRect(this.x, this.y, this.x + this.width, this.y + this.height, backgroundColor, backgroundColor);

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        if (icon == null) {
            mc.getTextureManager().bindTexture(ICON);
            drawModalRectWithCustomSizedTexture(this.x, this.y, 0, 0, this.width,
                    this.height, this.width, this.height);
        } else {
            mc.getTextureManager().bindTexture(icon.id());
            drawModalRectWithCustomSizedTexture(this.x, this.y, 0, 0, this.width,
                    this.height, this.width, this.height);
        }
        GlStateManager.disableBlend();

        String message = this.getMessage();
        List<String> split = TextMeasureUtil.split(font, message, 45);
        if (split.size() > 1) {
            renderCenteredString(font, split.get(0), this.x + this.width / 2, this.y + this.height - 19, 0x555555);
            renderCenteredString(font, split.get(1), this.x + this.width / 2, this.y + this.height - 10, 0x555555);
        } else {
            renderCenteredString(font, message, this.x + this.width / 2, this.y + this.height - 15, 0x555555);
        }

        if (this.isMouseOver()) {
            int sideColor = 0xFF_E1BEE7;
            this.drawGradientRect(this.x, this.y + 1, this.x + 1, this.y + this.height - 1, sideColor, sideColor);
            this.drawGradientRect(this.x, this.y, this.x + this.width, this.y + 1, sideColor, sideColor);
            this.drawGradientRect(this.x + this.width - 1, this.y + 1, this.x + this.width, this.y + this.height - 1, sideColor, sideColor);
            this.drawGradientRect(this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, sideColor, sideColor);
        }
    }

    public void renderComponentTooltip(GuiScreen screen, int pMouseX, int pMouseY) {
        String desc = LanguageManager.getI18n(pack, "description", pack.desc());
        if (StringUtils.isBlank(desc)) {
            return;
        }
        List<String> mutableComponents = Collections.singletonList(desc);
        if (this.isMouseOver()) {
            screen.drawHoveringText(mutableComponents, pMouseX, pMouseY);
        }
    }

    private static void renderCenteredString(FontRenderer font, String text, int x, int y, int color) {
        x -= font.getStringWidth(text) / 2;
        font.drawString(text, x, y, color, false);
    }

    @Override
    public void close() {
        closed = true;
        if (texture != null) {
            CustomTextureManager.release(texture);
            texture = null;
        }
        icon = null;
    }

    private static boolean isCancellation(@Nullable Throwable error) {
        return unwrap(error) instanceof CancellationException;
    }

    private static Throwable unwrap(@Nullable Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }
}
