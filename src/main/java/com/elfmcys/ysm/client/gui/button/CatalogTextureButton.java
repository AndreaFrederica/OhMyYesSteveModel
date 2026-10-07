package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.client.animation.AnimationRegister;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.util.GuiRenderUtil;
import com.elfmcys.ysm.util.RenderUtil;
import com.elfmcys.ysm.util.TextMeasureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.client.config.GuiUtils;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.List;

public final class CatalogTextureButton extends Button implements AutoCloseable {
    private final Hash256 modelHash;
    private final String path;
    private final String texture;
    private final CustomGuiPlayerEntity entity;
    private final SelectionHandler selection;
    private final ResourceRequest request;
    private @Nullable ResourceLease lease;
    private @Nullable ModelRenderTarget renderTarget;
    private @Nullable Throwable error;
    private boolean closed;

    public CatalogTextureButton(int x, int y, Hash256 modelHash, String path, String texture,
                                CustomGuiPlayerEntity entity, SelectionHandler selection) {
        super(x, y, 54, 102, texture, ignored -> { });
        this.modelHash = modelHash;
        this.path = path;
        this.texture = texture;
        this.entity = entity;
        this.selection = selection;
        var service = ClientModelService.instance();
        request = service.resourceRequest(modelHash, texture);
        lease = service.getOrStart(request);
    }

    @Override
    public void onPress() {
        selection.select(modelHash, path, texture, renderTarget);
    }

    @Override
    public void renderWidget(Minecraft mc, int mouseX, int mouseY, float partialTick) {
        updateLazyFailure();
        this.drawGradientRect(x, y, x + width, y + height, 0xFF434242, 0xFF434242);
        if (renderTarget == null || error != null) {
            var text = error == null ? ".".repeat((int) ((Minecraft.getSystemTime() / 250L) % 4L)) : "!";
            this.drawCenteredString(mc.fontRenderer, text, x + width / 2,
                    y + (height - 20) / 2, error == null ? 0xFFF3EFE0 : 0xFFFF5555);
        } else {
            renderEntity(partialTick);
        }
        renderName(mc);
        if (isMouseOver()) {
            this.drawGradientRect(x, y + 1, x + 1, y + height - 1,
                    0xFFF3EFE0, 0xFFF3EFE0);
            this.drawGradientRect(x, y, x + width, y + 1,
                    0xFFF3EFE0, 0xFFF3EFE0);
            this.drawGradientRect(x + width - 1, y + 1, x + width, y + height - 1,
                    0xFFF3EFE0, 0xFFF3EFE0);
            this.drawGradientRect(x, y + height - 1, x + width, y + height,
                    0xFFF3EFE0, 0xFFF3EFE0);
        }
    }

    private void updateLazyFailure() {
        pollLease();
        if (lease != null && !lease.isCurrent(request)) {
            releaseLease();
            renderTarget = null;
            error = new IllegalStateException("The model content changed while this page was open");
            return;
        }
        if (error != null || renderTarget == null || renderTarget.playerResources() == null) {
            return;
        }
        var resources = renderTarget.playerResources();
        if (resources.animations().hasFailures()
                || resources.fpArmAnimations().hasFailures()) {
            error = new IllegalStateException("One or more preview animations failed to load");
        }
        if (error != null) {
            releaseLease();
            renderTarget = null;
        }
    }

    private void pollLease() {
        if (closed || lease == null || renderTarget != null || error != null) {
            return;
        }
        var result = lease.poll();
        if (result instanceof AcquireResult.Failed failed) {
            error = failed.failure().cause();
            releaseLease();
        } else if (result instanceof AcquireResult.Ready ready) {
            renderTarget = ready.target();
            entity.reset();
            entity.getPreviewInfo().setPreview(AnimationRegister.IDLE);
            entity.updateModelAndTexture(modelHash, texture);
        }
    }

    @Override
    public void close() {
        closed = true;
        entity.reset();
        releaseLease();
        renderTarget = null;
    }

    private void releaseLease() {
        var current = lease;
        lease = null;
        if (current != null) {
            current.cancelPending();
        }
    }

    private void renderEntity(float partialTick) {
        GuiRenderUtil.enableScissor(x, y, width, height - 20);
        RenderUtil.renderModelInGui(x + width / 2f, y + height / 2f + 24f, 35f,
                partialTick, entity,
                RegisterEntityRenderersEvent.getPlayerRenderer(), false, true);
        GuiRenderUtil.disableScissor();
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
    }

    private void renderName(Minecraft mc) {
        FontRenderer font = mc.fontRenderer;
        var name = renderTarget == null ? texture
                : LanguageManager.getI18n(renderTarget, "files.player.texture.%s".formatted(texture), texture);
        List<String> split = TextMeasureUtil.split(font, name, 50);
        if (split.size() > 1) {
            this.drawCenteredString(font, split.get(0), x + width / 2, y + height - 19, 0xF3EFE0);
            this.drawCenteredString(font, split.get(1), x + width / 2, y + height - 10, 0xF3EFE0);
        } else {
            this.drawCenteredString(font, name, x + width / 2,
                    y + height - 15, 0xF3EFE0);
        }
    }

    @FunctionalInterface
    public interface SelectionHandler {
        void select(Hash256 hash, String path, String texture, @Nullable ModelRenderTarget renderTarget);
    }
}
