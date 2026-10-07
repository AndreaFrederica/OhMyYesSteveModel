package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.accessor.ILivingRenderer;
import com.elfmcys.ysm.api.rendering.v0.TargetKind;
import com.elfmcys.ysm.api.rendering.v0.event.RenderLayerEvent;
import com.elfmcys.ysm.api.rendering.v0.event.RenderModelEvent;
import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.compat.backport.BackportCompat;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.geckolib3.core.util.MathUtil;
import com.elfmcys.ysm.mclib.utils.Interpolations;
import com.elfmcys.ysm.natives.render.RenderContextType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.List;

public abstract class GeoReplacedEntityRenderer<TEntity extends EntityLivingBase, T extends CustomHumanoidEntity<TEntity>> extends RenderLivingBase<TEntity> implements IGeoRenderer<T> {
    protected final List<GeoLayerRenderer<T>> layerRenderers = new ObjectArrayList<>();

    protected GeoReplacedEntityRenderer(RenderManager renderManager) {
        super(renderManager, new ModelPlayer(0.0F, true), 0.5F);
    }

    public void renderAnimatableEntity(T animatableEntity, double x, double y, double z,
                                       float entityYaw, float partialTick) {
        renderAnimatableEntity(animatableEntity, null, x, y, z, entityYaw, partialTick);
    }

    public void renderAnimatableEntity(T animatableEntity, @Nullable ResourceLocation textureOverride,
                                       double x, double y, double z, float entityYaw, float partialTick) {
        if (MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Pre<>(animatableEntity.getEntity(), this, partialTick, x, y, z)))
            return;
        final TEntity entity = animatableEntity.getEntity();
        var mc = Minecraft.getMinecraft();
        var data = animatableEntity.update(partialTick);
        if (data != null) {
            data.packedLight = data.ctx.nativeType() == RenderContextType.GUI
                    ? 0x7FFF7FFF
                    : entity.getBrightnessForRender();
        }
        if (data != null && mc.player != null) {
            GlStateManager.pushMatrix();
            GlStateManager.disableCull();
            try {

                renderLivingAt(entity, x, y, z);
                applyRotations(entity, data.animationData.lerpedAge, data.animationData.lerpBodyRot, partialTick);

                if (animatableEntity.getEntity().getRidingEntity() != null) {
                    var cap = animatableEntity.getEntity().getRidingEntity().getCapability(VehicleAnimatableCapabilityProvider.CAP, null);
                    if (cap != null) {
                        var rot = cap.getRotation();
                        if (rot != null) {
                            GlStateManager.rotate(-MathUtil.radiansToDegrees(rot.x), 1, 0, 0);
                            GlStateManager.rotate(-MathUtil.radiansToDegrees(rot.z), 0, 0, 1);
                        }
                    }
                }

                GlStateManager.enableRescaleNormal();
                GlStateManager.enableAlpha();
                GlStateManager.translate(0, 0.01f, 0);

                if (textureOverride != null) {
                    data.texture = textureOverride;
                }
                var bodyVisible = this.isVisible(entity);
                var ghost = !bodyVisible && !entity.isInvisibleToPlayer(mc.player);

                var renderLayersFirst = data.renderLayersFirst;

                boolean scoreTeamColor = false;
                boolean mainBrightness = false;

                if (this.renderOutlines) {
                    scoreTeamColor = this.setScoreTeamColor(entity);
                    GlStateManager.enableColorMaterial();
                    GlStateManager.enableOutlineMode(this.getTeamColor(entity));
                } else {
                    mainBrightness = this.setDoRenderBrightness(entity, partialTick);
                }

                if (bodyVisible || ghost) {
                    preRender(data, animatableEntity, Color.WHITE);
                    if (renderLayersFirst && (!(entity instanceof EntityPlayer player) || !player.isSpectator())) {
                        renderLayer(animatableEntity, data, x, y, z);
                    }

                    if (ghost) GlStateManager.enableBlendProfile(GlStateManager.Profile.TRANSPARENT_MODEL);
                    if (data.modelState.isValid()) {
                        var event = new RenderModelEvent(animatableEntity.getEntity(),
                                TargetKind.PLAYER,
                                data,
                                Color.WHITE.getColor());
                        if (!YesSteveModel.postEvent(event)) {
                            render(data, animatableEntity, Color.WHITE);
                        }
                    }
                    if (ghost) GlStateManager.disableBlendProfile(GlStateManager.Profile.TRANSPARENT_MODEL);

                    if (!this.renderOutlines) {
                        if (mainBrightness) this.unsetBrightness();
                        GlStateManager.depthMask(true);
                    }

                    if (!renderLayersFirst && (!(entity instanceof EntityPlayer player) || !player.isSpectator())) {
                        renderLayer(animatableEntity, data, x, y, z);
                    }

                    if (this.renderOutlines) {
                        if (scoreTeamColor) this.unsetScoreTeamColor();
                        GlStateManager.disableOutlineMode();
                        GlStateManager.disableColorMaterial();
                    }

                    postRender(data, animatableEntity, Color.WHITE);
                }
            } finally {
                GlStateManager.disableRescaleNormal();
                GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
                GlStateManager.enableTexture2D();
                GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
                GlStateManager.enableCull();
                GlStateManager.popMatrix();
            }
        }

        if (this.renderManager.renderViewEntity != null) {
            ((ILivingRenderer) this).ysm$renderNameTag(entity, x, y, z, entityYaw, partialTick);
        }

        MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Post<>(entity, this, partialTick, x, y, z));

        if (!this.renderOutlines && entity instanceof EntityLiving mob) {
            Entity leashHolder = mob.getLeashHolder();
            //noinspection ConstantValue
            if (leashHolder != null) {
                this.renderLeash(entity, x, y, z, entityYaw, partialTick, leashHolder);
            }
        }
    }

    protected void renderLayer(T animatable, GeoRenderData renderData, double x, double y, double z) {
        var event = new RenderLayerEvent(animatable.getEntity(),
                TargetKind.PLAYER,
                renderData);
        if (!YesSteveModel.postEvent(event)) {
            for (GeoLayerRenderer<T> layerRenderer : this.layerRenderers) {
                layerRenderer.render(animatable, renderData, x, y, z);
            }
        }
    }

    @Override
    protected void renderLivingAt(@NotNull TEntity entity, double x, double y, double z) {
        // RenderPlayer#renderLivingAt
        if (entity instanceof EntityPlayer player && entity.isEntityAlive() && entity.isPlayerSleeping()) {
            super.renderLivingAt(entity, x + player.renderOffsetX, y + player.renderOffsetY, z + player.renderOffsetZ);
            return;
        }

        super.renderLivingAt(entity, x, y, z);
    }

    @Override
    protected void applyRotations(@NotNull TEntity pEntityLiving, float pAgeInTicks, float pRotationYaw, float pPartialTicks) {
        // RenderPlayer#applyRotations
        if (pEntityLiving instanceof EntityPlayer player && pEntityLiving.isEntityAlive() && pEntityLiving.isPlayerSleeping()) {
            GlStateManager.rotate(player.getBedOrientationInDegrees(), 0, 1, 0);
            GlStateManager.rotate(this.getDeathMaxRotation(pEntityLiving), 0, 0, 1);
            GlStateManager.rotate(270.0F, 0, 1, 0);
            return;
        }

        int deathTime = pEntityLiving.deathTime;
        boolean autoSpineAttach = BackportCompat.getTridentAutoSpinAttack(pEntityLiving);
        if (deathTime > 0) {
            pEntityLiving.deathTime = 0;
        }
        if (autoSpineAttach) {
            BackportCompat.setTridentAutoSpinAttack(pEntityLiving, false);
        }

        // 爬梯时，禁止旋转
        if (pEntityLiving.isOnLadder()) {
            BlockPos climbablePos = new BlockPos(pEntityLiving.posX, pEntityLiving.getEntityBoundingBox().minY, pEntityLiving.posZ);
            IBlockState blockState = pEntityLiving.world.getBlockState(climbablePos);
            if (blockState.getPropertyKeys().contains(BlockHorizontal.FACING)) {
                pRotationYaw = blockState.getValue(BlockHorizontal.FACING).getOpposite().getHorizontalIndex() * 90;
            }
        }

        super.applyRotations(pEntityLiving, pAgeInTicks, pRotationYaw, pPartialTicks);

        if (deathTime > 0) {
            pEntityLiving.deathTime = deathTime;
        }
        if (autoSpineAttach) {
            BackportCompat.setTridentAutoSpinAttack(pEntityLiving, true);
        }
    }

    @Override
    public boolean canRenderName(@NotNull TEntity entity) {
        return entity == this.renderManager.pointedEntity && entity.hasCustomName() && Minecraft.isGuiEnabled();
    }

    public final boolean addLayer(GeoLayerRenderer<T> layer) {
        return this.layerRenderers.add(layer);
    }

    protected <THolder extends Entity> void renderLeash(
            TEntity entity, double x, double y, double z,
            float entityYaw, float partialTicks, THolder leashHolder
    ) {
        // RenderLiving#renderLeash
        y = y - (1.6D - entity.height) * 0.5D;
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buffer = tess.getBuffer();

        double holderYawRad = Math.toRadians(Interpolations.lerp(leashHolder.prevRotationYaw, leashHolder.rotationYaw, partialTicks * 0.5F));
        double holderPitchRad = Math.toRadians(Interpolations.lerp(leashHolder.prevRotationPitch, leashHolder.rotationPitch, partialTicks * 0.5F));
        double cosHolderYaw = Math.cos(holderYawRad);
        double sinHolderYaw = Math.sin(holderYawRad);
        double sinHolderPitch = Math.sin(holderPitchRad);
        if (leashHolder instanceof EntityHanging) {
            cosHolderYaw = 0.0D;
            sinHolderYaw = 0.0D;
            sinHolderPitch = -1.0D;
        }
        double cosHolderPitch = Math.cos(holderPitchRad);

        double holderX = Interpolations.lerp(leashHolder.prevPosX, leashHolder.posX, partialTicks)
                - cosHolderYaw * 0.7D - sinHolderYaw * 0.5D * cosHolderPitch;
        double holderY = Interpolations.lerp(leashHolder.prevPosY + leashHolder.getEyeHeight() * 0.7D, leashHolder.posY + leashHolder.getEyeHeight() * 0.7D, partialTicks)
                - sinHolderPitch * 0.5D - 0.25D;
        double holderZ = Interpolations.lerp(leashHolder.prevPosZ, leashHolder.posZ, partialTicks)
                - sinHolderYaw * 0.7D + cosHolderYaw * 0.5D * cosHolderPitch;

        double entityYawRad = Math.toRadians(Interpolations.lerp(entity.prevRenderYawOffset, entity.renderYawOffset, partialTicks)) + (Math.PI / 2D);

        double neckOffsetX = Math.cos(entityYawRad) * entity.width * 0.4D;
        double neckOffsetZ = Math.sin(entityYawRad) * entity.width * 0.4D;
        double entityNeckX = Interpolations.lerp(entity.prevPosX, entity.posX, partialTicks) + neckOffsetX;
        double entityNeckY = Interpolations.lerp(entity.prevPosY, entity.posY, partialTicks);
        double entityNeckZ = Interpolations.lerp(entity.prevPosZ, entity.posZ, partialTicks) + neckOffsetZ;
        x += neckOffsetX;
        z += neckOffsetZ;
        double diffX = holderX - entityNeckX;
        double diffY = holderY - entityNeckY;
        double diffZ = holderZ - entityNeckZ;

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();

        // 拴绳的一个面
        buffer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int segment = 0; segment <= 24; ++segment) {
            // 初始颜色
            float r = 0.5F;
            float g = 0.4F;
            float b = 0.3F;

            // 偶数段颜色减深
            if (segment % 2 == 0) {
                r *= 0.7F;
                g *= 0.7F;
                b *= 0.7F;
            }

            float progress = segment / 24.0F;
            double hangOffset = progress * progress + progress;
            double yOffset = (24.0F - segment) / 18.0F + 0.125F;

            buffer.pos(x + diffX * progress + 0.0D, y + diffY * hangOffset * 0.5D + yOffset, z + diffZ * progress).color(r, g, b, 1.0F).endVertex();
            buffer.pos(x + diffX * progress + 0.025D, y + diffY * hangOffset * 0.5D + yOffset + 0.025D, z + diffZ * progress).color(r, g, b, 1.0F).endVertex();
        }
        tess.draw();

        // 拴绳的另一个面
        buffer.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int segment = 0; segment <= 24; ++segment) {
            // 初始颜色
            float r = 0.5F;
            float g = 0.4F;
            float b = 0.3F;

            // 偶数段颜色减深
            if (segment % 2 == 0) {
                r *= 0.7F;
                g *= 0.7F;
                b *= 0.7F;
            }

            float progress = segment / 24.0F;
            double hangOffset = progress * progress + progress;
            double yOffset = (24.0F - segment) / 18.0F + 0.125F;

            buffer.pos(x + diffX * progress + 0.0D, y + diffY * hangOffset * 0.5D + yOffset + 0.025D, z + diffZ * progress).color(r, g, b, 1.0F).endVertex();
            buffer.pos(x + diffX * progress + 0.025D, y + diffY * hangOffset * 0.5D + yOffset, z + diffZ * progress + 0.025D).color(r, g, b, 1.0F).endVertex();
        }
        tess.draw();

        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
    }
}
