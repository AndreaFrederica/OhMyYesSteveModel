package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.entity.CustomFirstPersonArmEntity;
import com.elfmcys.ysm.event.api.SpecialPlayerRenderEvent;
import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.geckolib3.geo.GeoVertexFormat;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.EnumHandSide;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.opengl.GL11;

public class CustomFirstPersonArmRenderer {
    private CustomFirstPersonArmEntity armEntity = null;

    public void render(EntityPlayerSP player, PlayerAnimatableCapability cap, EnumHandSide arm,
                       float partialTick) {
        if (armEntity == null || armEntity.getEntity() != player) {
            armEntity = new CustomFirstPersonArmEntity(player, cap);
        }

        armEntity.checkModelUpdate();
        var data = armEntity.update(partialTick);
        if (data == null || !data.modelState.isValid()) {
            return;
        }

        var renderEvent = new SpecialPlayerRenderEvent(player, cap, cap.getModelId());
        if (MinecraftForge.EVENT_BUS.post(renderEvent)) {
            return;
        }

        var textureLocation = renderEvent.getTextureLocationOverride() == null ? cap.getTextureLocation() : renderEvent.getTextureLocationOverride();

        GlStateManager.pushMatrix();
        if (arm == EnumHandSide.LEFT) {
            GlStateManager.translate(0.25, 1.8, 0);
        } else {
            GlStateManager.translate(-0.25, 1.8, 0);
        }
        GlStateManager.scale(-1, -1, 1);

        var tess = Tessellator.getInstance();
        var buffer = tess.getBuffer();
        buffer.begin(GL11.GL_QUADS, GeoVertexFormat.NEW_ENTITY);
        NativeRenderer.render(buffer, data.modelState.getNativeState(), data.modelState.getVertexCount(),
                0x7FFF7FFF, 0, Color.WHITE.getColor(), data.ctx.nativeType());
        tess.draw();
        GlStateManager.popMatrix();
    }
}
