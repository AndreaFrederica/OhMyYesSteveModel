package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import org.lwjgl.opengl.GL11;

public final class GeoRenderType {
    private GeoRenderType() {
    }

    public static void commit(BufferBuilder buffer, GeoRenderData data, Color color) {
        var modelState = data.modelState;
        var vertexCount = modelState.getVertexCount();
        if (vertexCount == 0) {
            return;
        }

        Minecraft.getMinecraft().getTextureManager().bindTexture(data.texture);
        GlStateManager.enableCull();
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean translucent = modelState.hasTranslucentVertices();
        if (translucent) {
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        }

        buffer.begin(GL11.GL_QUADS, GeoVertexFormat.NEW_ENTITY);
        try {
            NativeRenderer.render(buffer, modelState.getNativeState(), vertexCount,
                    data.packedLight, 0, color.getColor(), data.ctx.nativeType());
        } finally {
            Tessellator.getInstance().draw();
            GlStateManager.disableCull();
            if (translucent && !blend) {
                GlStateManager.disableBlend();
            }
        }
    }
}
