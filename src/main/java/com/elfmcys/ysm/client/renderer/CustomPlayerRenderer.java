package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.client.renderer.layer.CustomParrotOnShoulderLayer;
import com.elfmcys.ysm.client.renderer.layer.CustomPlayerElytraLayer;
import com.elfmcys.ysm.client.renderer.layer.CustomPlayerHeadLayer;
import com.elfmcys.ysm.client.renderer.layer.CustomPlayerItemInHandLayer;
import com.elfmcys.ysm.event.api.SpecialPlayerRenderEvent;
import com.elfmcys.ysm.geckolib3.geo.GeoReplacedEntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.NotNull;

public class CustomPlayerRenderer extends GeoReplacedEntityRenderer<EntityPlayer, CustomPlayerEntity> {
    private ResourceLocation textureOverride;

    public CustomPlayerRenderer(RenderManager ctx) {
        super(ctx);
        addLayer(new CustomPlayerItemInHandLayer());
        addLayer(new CustomPlayerElytraLayer(ctx));
        addLayer(new CustomParrotOnShoulderLayer(ctx));
        addLayer(new CustomPlayerHeadLayer());
    }

    @Override
    public void doRender(EntityPlayer player, double x, double y, double z, float entityYaw, float partialTicks) {
        /*
        // 检查卓越前线的隐藏功能
        if (SWarfareCompat.shouldHidePlayerRender(player)) {
            return;
        }
        */

        PlayerAnimatableCapability cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
        if (cap == null) {
            return;
        }

        cap.checkModelUpdate();
        var event = new SpecialPlayerRenderEvent(player, cap, cap.getModelId());
        textureOverride = event.getTextureLocationOverride();
        if (MinecraftForge.EVENT_BUS.post(event)) {
            return;
        }

        renderAnimatableEntity(cap, event.getTextureLocationOverride(), x, y, z, entityYaw, partialTicks);
    }

    @Override
    @NotNull
    protected ResourceLocation getEntityTexture(EntityPlayer pEntity) {
        if (textureOverride != null) {
            return textureOverride;
        }
        PlayerAnimatableCapability cap = pEntity.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
        return cap == null ? TextureMap.LOCATION_MISSING_TEXTURE : cap.getTextureLocation();
    }

    @Override
    protected void renderEntityName(EntityPlayer player, double x, double y, double z, String displayName, double distanceSq) {
        if (CustomGuiPlayerEntity.isFakePlayer(player)) {
            return;
        }
        if (distanceSq < 100) {
            Scoreboard scoreboard = player.getWorldScoreboard();
            ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(2);
            if (objective != null) {
                Score score = scoreboard.getOrCreateScore(player.getName(), objective);
                this.renderLivingLabel(player, score.getScorePoints() + " " + objective.getDisplayName(), x, y, z, 64);
                y += this.getFontRendererFromRenderManager().FONT_HEIGHT * 1.15 * 0.025;
            }
        }
        super.renderEntityName(player, x, y, z, displayName, distanceSq);
    }

    @Override
    protected void applyRotations(EntityPlayer player, float pAgeInTicks, float pRotationYaw, float pPartialTicks) {
        super.applyRotations(player, pAgeInTicks, pRotationYaw, pPartialTicks);
        // 如果是坐在女仆的实体上，则需要偏移回去（哎，屎山代码+1006）
        Entity vehicle = player.getRidingEntity();
        if (TlmClientCompat.isChair(vehicle) || TlmClientCompat.isSit(vehicle)) {
            GlStateManager.translate(0, 0.5F, 0);
        }
    }
}
