package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.accessor.ILivingRenderer;
import com.elfmcys.ysm.api.rendering.v0.TargetKind;
import com.elfmcys.ysm.api.rendering.v0.event.RenderLayerEvent;
import com.elfmcys.ysm.api.rendering.v0.event.RenderModelEvent;
import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.geckolib3.util.EModelRenderCycle;
import com.elfmcys.ysm.mixin.client.LivingEntityAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

public abstract class GeoReplacedEntityRenderer<TEntity extends LivingEntity, T extends CustomHumanoidEntity<TEntity>> extends LivingEntityRenderer<TEntity, PlayerModel<TEntity>> implements IGeoRenderer<T> {
    protected final List<GeoLayerRenderer<T>> layerRenderers = new ObjectArrayList<>();

    public GeoReplacedEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5F);
    }

    public static int getPackedOverlay(LivingEntity entity, float u) {
        return OverlayTexture.pack(OverlayTexture.u(u), OverlayTexture.v(entity.hurtTime > 0 || entity.deathTime > 0));
    }

    public void renderAnimatableEntity(T animatableEntity, float entityYaw, float partialTick,
                                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        renderAnimatableEntity(animatableEntity, null,  entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    public void renderAnimatableEntity(T animatableEntity, @Nullable ResourceLocation textureOverride, float entityYaw, float partialTick,
                                       PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Pre<>(animatableEntity.getEntity(), this, partialTick, poseStack, bufferSource, packedLight)))
            return;
        final TEntity entity = animatableEntity.getEntity();
        var mc = Minecraft.getInstance();
        var scene=animatableEntity.updateGeneralMesh(partialTick);
        if(scene!=null) {
            if(mc.player!=null && isBodyVisible(entity) && !entity.isInvisibleTo(mc.player)) {
                poseStack.pushPose();
                try {
                    if(entity.getPose()==Pose.SLEEPING && entity.getBedOrientation()!=null) {
                        var direction=entity.getBedOrientation();float offset=entity.getEyeHeight(Pose.STANDING)-.1f;
                        poseStack.translate(-direction.getStepX()*offset,0,-direction.getStepZ()*offset);
                    }
                    var presentation=animatableEntity.scenePresentation(partialTick);
                    setupRotations(entity,poseStack,presentation.lerpedAge,presentation.lerpBodyRot,partialTick);
                    if(entity.getVehicle()!=null) entity.getVehicle().getCapability(VehicleAnimatableCapabilityProvider.CAP).ifPresent(cap->{
                        var rotation=cap.getRotation();
                        if(rotation!=null) poseStack.mulPose(new Quaternionf().rotateZYX(rotation.z,0,rotation.x).invert());
                    });
                    poseStack.translate(0,.01,0);
                    com.elfmcys.ysm.client.renderer.SceneEntityRenderer.render(animatableEntity,scene,poseStack,bufferSource,packedLight);
                } finally { poseStack.popPose(); }
            }
            ((ILivingRenderer)this).ysm$renderNameTag(entity,entityYaw,partialTick,poseStack,bufferSource,packedLight);
            MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Post<>(entity,this,partialTick,poseStack,bufferSource,packedLight));
            return;
        }
        var data = animatableEntity.update(partialTick);
        if (data != null && mc.player != null) {
            poseStack.pushPose();
            try {

                if (entity.getPose() == Pose.SLEEPING) {
                    Direction direction = entity.getBedOrientation();
                    if (direction != null) {
                        float eyeOffset = entity.getEyeHeight(Pose.STANDING) - 0.1f;
                        poseStack.translate(-direction.getStepX() * eyeOffset, 0, -direction.getStepZ() * eyeOffset);
                    }
                }

                setupRotations(entity, poseStack, data.animationData.lerpedAge, data.animationData.lerpBodyRot, partialTick);

                if (animatableEntity.getEntity().getVehicle() != null) {
                    animatableEntity.getEntity().getVehicle().getCapability(VehicleAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
                        var rot = cap.getRotation();
                        if (rot != null) {
                            poseStack.mulPose(new Quaternionf().rotateZYX(rot.z, 0, rot.x).invert());
                        }
                    });
                }

                poseStack.translate(0, 0.01f, 0);

                var texture = textureOverride == null ? data.texture : textureOverride;
                var bodyVisible = this.isBodyVisible(entity) && !entity.isInvisibleTo(mc.player);
                var glowing = mc.shouldEntityAppearGlowing(entity);
                var renderType = getRenderType(texture,
                        bodyVisible, glowing,
                        data.modelState.hasTranslucentVertices());

                var renderLayersFirst = data.renderLayersFirst;
                var packedOverlay = getPackedOverlay(entity, getOverlayProgress(entity, partialTick));

                if (renderType != null) {
                    preRender(data, animatableEntity, poseStack, bufferSource,
                            packedLight, packedOverlay, Color.WHITE);
                    if (renderLayersFirst && !entity.isSpectator()) {
                        renderLayer(poseStack, bufferSource, animatableEntity, data, packedLight, packedOverlay);
                    }

                    if (data.modelState.isValid()) {
                        var event = new RenderModelEvent(animatableEntity.getEntity(),
                                animatableEntity.renderTargetKind(),
                                data,
                                bufferSource,
                                renderType,
                                poseStack,
                                packedLight,
                                packedOverlay,
                                Color.WHITE.getColor());
                        if (!YesSteveModel.postEvent(event)) {
                            render(data, animatableEntity, renderType, poseStack, bufferSource,
                                    packedLight, packedOverlay, Color.WHITE);
                        }
                    }

                    if (!renderLayersFirst && !entity.isSpectator()) {
                        renderLayer(poseStack, bufferSource, animatableEntity, data, packedLight, packedOverlay);
                    }
                    postRender(data, animatableEntity, poseStack, bufferSource,
                            packedLight, packedOverlay, Color.WHITE);
                }
            } finally {
                poseStack.popPose();
            }
        }

        ((ILivingRenderer) this).ysm$renderNameTag(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Post<>(entity, this, partialTick, poseStack, bufferSource, packedLight));
    }

    protected void renderLayer(PoseStack poseStack, MultiBufferSource buffer, T animatable, GeoRenderData renderData, int packedLight, int overlay) {
        var event = new RenderLayerEvent(animatable.getEntity(),
                animatable.renderTargetKind(),
                renderData,
                poseStack,
                buffer,
                packedLight,
                overlay);
        if (!YesSteveModel.postEvent(event)) {
            for (GeoLayerRenderer<T> layerRenderer : this.layerRenderers) {
                layerRenderer.render(poseStack, buffer, animatable, renderData, packedLight, overlay);
            }
        }
    }

    protected float getOverlayProgress(TEntity entity, float partialTicks) {
        return 0.0F;
    }

    @Override
    protected void setupRotations(TEntity pEntityLiving, @NotNull PoseStack poseStack, float pAgeInTicks, float pRotationYaw, float pPartialTicks) {
        int deathTime = pEntityLiving.deathTime;
        boolean autoSpineAttach = pEntityLiving.isAutoSpinAttack();
        if (deathTime > 0) {
            pEntityLiving.deathTime = 0;
        }
        if (autoSpineAttach) {
            ((LivingEntityAccessor) pEntityLiving).setFlag(4, false);
        }

        // 爬梯时，禁止旋转
        if (pEntityLiving.onClimbable()) {
            Optional<BlockPos> climbablePos = pEntityLiving.getLastClimbablePos();
            if (climbablePos.isPresent()) {
                BlockState blockState = pEntityLiving.level().getBlockState(climbablePos.get());
                Optional<Direction> optionalValue = blockState.getOptionalValue(HorizontalDirectionalBlock.FACING);
                if (optionalValue.isPresent()) {
                    pRotationYaw = optionalValue.get().getOpposite().get2DDataValue() * 90;
                }
            }
        }

        super.setupRotations(pEntityLiving, poseStack, pAgeInTicks, pRotationYaw, pPartialTicks);

        if (deathTime > 0) {
            pEntityLiving.deathTime = deathTime;
        }
        if (autoSpineAttach) {
            ((LivingEntityAccessor) pEntityLiving).setFlag(4, true);
        }
    }

    @Override
    public boolean shouldShowName(TEntity entity) {
        double nameRenderDistance = entity.isDiscrete() ? 32d : 64d;
        if (this.entityRenderDispatcher.distanceToSqr(entity) >= nameRenderDistance * nameRenderDistance) {
            return false;
        }
        return entity == this.entityRenderDispatcher.crosshairPickEntity && entity.hasCustomName() && Minecraft.renderNames();
    }

    public final boolean addLayer(GeoLayerRenderer<T> layer) {
        return this.layerRenderers.add(layer);
    }
}
