package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ScreenShotHelper;

import java.awt.image.BufferedImage;

/** Draws one already-published player target into a private host framebuffer. */
public final class PreviewRenderHost {
    public static final int WIDTH = 256;
    public static final int HEIGHT = 256;

    private PreviewRenderHost() {
    }

    public static BufferedImage render(ResourceRequest request, ResourceLease lease) {
        RenderUtil.assertOnMinecraftThread();
        var acquired = lease.poll();
        if (!(acquired instanceof AcquireResult.Ready ready)) {
            throw new IllegalArgumentException("Preview rendering requires a ready target");
        }
        var target = ready.target();
        if (target.playerResources() == null) {
            throw new IllegalArgumentException("Preview rendering requires a player target");
        }

        var entity = new CustomGuiPlayerEntity();
        try {
            entity.installPreviewResource(request, new BorrowedTargetLease(request, target));
            return renderEntity(entity, target);
        } finally {
            entity.reset();
        }
    }

    private static BufferedImage renderEntity(CustomGuiPlayerEntity entity,
                                              ModelRenderTarget target) {
        var minecraft = Minecraft.getMinecraft();
        var mainTarget = minecraft.getFramebuffer();
        Framebuffer offscreen = null;
        Throwable primaryFailure = null;
        // 1.12.2 是固定管线：投影与模型视图由 GlStateManager 矩阵栈携带，借助矩阵栈保存/恢复现场
        GlStateManager.pushMatrix();
        try {
            GlStateManager.matrixMode(5889);
            GlStateManager.pushMatrix();
            try {
                GlStateManager.loadIdentity();
                GlStateManager.ortho(0.0, WIDTH, HEIGHT, 0.0, 1_000.0, 3_000.0);
                GlStateManager.matrixMode(5888);
                GlStateManager.loadIdentity();

                offscreen = new Framebuffer(WIDTH, HEIGHT, true);
                offscreen.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
                offscreen.framebufferClear();
                offscreen.bindFramebuffer(true);
                RenderUtil.renderModelInGui(WIDTH / 2.0F, HEIGHT * 0.68F, 88.0F,
                        minecraft.getRenderPartialTicks(), entity,
                        RegisterEntityRenderersEvent.getPlayerRenderer(),
                        target.info().getSettings().disablePreviewRotation(), true);
                return ScreenShotHelper.createScreenshot(WIDTH, HEIGHT, offscreen);
            } finally {
                GlStateManager.matrixMode(5889);
                GlStateManager.popMatrix();
                GlStateManager.matrixMode(5888);
            }
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = null;
            if (offscreen != null) {
                try {
                    offscreen.deleteFramebuffer();
                } catch (RuntimeException | Error failure) {
                    cleanupFailure = failure;
                }
            }
            try {
                mainTarget.bindFramebuffer(true);
            } catch (RuntimeException | Error failure) {
                cleanupFailure = append(cleanupFailure, failure);
            }
            try {
                GlStateManager.popMatrix();
            } catch (RuntimeException | Error failure) {
                cleanupFailure = append(cleanupFailure, failure);
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else if (cleanupFailure instanceof Error error) {
                    throw error;
                } else {
                    throw (RuntimeException) cleanupFailure;
                }
            }
        }
    }

    private static Throwable append(Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private record BorrowedTargetLease(ResourceRequest request,
                                       ModelRenderTarget target) implements ResourceLease {
        @Override
        public AcquireResult poll() {
            return new AcquireResult.Ready(target);
        }

        @Override
        public boolean isCurrent(ResourceRequest candidate) {
            return request.equals(candidate);
        }

        @Override
        public void cancelPending() {
        }

        @Override
        public void close() {
        }
    }
}
