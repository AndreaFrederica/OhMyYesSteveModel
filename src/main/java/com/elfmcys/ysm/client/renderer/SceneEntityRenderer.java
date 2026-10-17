package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.api.rendering.v0.SceneView;
import com.elfmcys.ysm.api.rendering.v0.SceneLightmap;
import com.elfmcys.ysm.mixin.client.LightTextureAccessor;
import com.elfmcys.ysm.client.entity.CustomEntity;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.util.RenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;

/** Host placement and ordinary-world presentation only. Lib owns deformation and source animation/physics. */
public final class SceneEntityRenderer {
    private SceneEntityRenderer() {}

    public static boolean render(CustomEntity<?> entity,GeneralMeshInstance instance,PoseStack pose,
                              MultiBufferSource buffers,int packedLight) {
        return render(entity, instance, pose, buffers, packedLight,
                SceneViewPlacement.worldFirstPerson(RenderUtil.extractRenderContext()),false);
    }

    /**
     * Renders a scene with an explicit view selection.  The normal entity path
     * derives this from the active compatibility adapter, while the vanilla
     * RenderArmEvent path has no such adapter and must explicitly request the
     * first-person geometry.
     */
    public static boolean render(CustomEntity<?> entity,GeneralMeshInstance instance,PoseStack pose,
                              MultiBufferSource buffers,int packedLight, boolean firstPerson) {
        return render(entity,instance,pose,buffers,packedLight,firstPerson,true);
    }
    private static boolean render(CustomEntity<?> entity,GeneralMeshInstance instance,PoseStack pose,
                                  MultiBufferSource buffers,int packedLight,boolean firstPerson,boolean cameraRelative) {
        var context=RenderUtil.extractRenderContext();
        if (context.firstPersonMod() != firstPerson) {
            context = new RenderContext(context.level(), context.irisShadow(), firstPerson,
                    context.inventory(), context.paperDoll(), context.offScreen(), context.immutable());
        }
        var frame = firstPerson ? instance.frame().firstPerson() : instance.frame().thirdPerson();
        var modelView=new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose())
                .scale(entity.getWidthScale(),entity.getHeightScale(),entity.getWidthScale());
        // EntityRenderer's canonical front is -Z; glTF's canonical avatar front after handedness conversion is +Z.
        var placement=instance.viewPlacement(firstPerson,cameraRelative);
        modelView.rotateY((float)Math.PI).mul(placement).mul(sourceToMeters(frame.coordinates()));
        var projection=RenderSystem.getProjectionMatrix();
        var lightmap = SceneLightmap.NONE;
        if (!context.inventory() && !context.paperDoll() && !context.offScreen()) {
            var gameLight = net.minecraft.client.Minecraft.getInstance().gameRenderer.lightTexture();
            lightmap = new SceneLightmap(((LightTextureAccessor)gameLight).ysm$lightTexture().getId(),
                    LightTexture.block(packedLight), LightTexture.sky(packedLight));
        }
        // Neutral host shading; source light/shadow tracks remain exposed through RenderSceneEvent.
        var view=new SceneView(new Matrix4(modelView.get(new float[16])),new Matrix4(projection.get(new float[16])),
                new Vec3(.3f,.7f,1),new Vec3(1,1,1),
                new Vec3(.2f,.2f,.2f),new FloatData(1,1,1,1),projection.m33()!=0,lightmap);
        instance.renderedHeldItems=0;
        if(!ClientModelService.instance().sceneRenderer().render(instance,entity.getEntity(),entity.renderTargetKind(),
                context,firstPerson,view,buffers))return false;
        instance.renderedHeldItems=instance.hasModelItemSockets()
                ? renderHeldItems(entity,instance,pose,buffers,packedLight,firstPerson,placement) : 0;
        if(firstPerson&&!cameraRelative&&entity.getEntity()==net.minecraft.client.Minecraft.getInstance().getCameraEntity())
            instance.submittedFirstPersonWorld(com.elfmcys.ysm.client.event.ClientTickEvent.renderSequence(),instance.renderedHeldItems);
        if(instance.debugSkeleton) {
            var skeleton=SceneSkeleton.of(instance.assets());var points=new java.util.ArrayList<GeneralMeshInstance.BonePoint>();
            var details=instance.frame().details();var window=net.minecraft.client.Minecraft.getInstance().getWindow();
            var transform=new Matrix4f(projection).mul(modelView);
            for(var bone:skeleton) {
                Vec3 position=bone.position();
                if(details instanceof ScenePackagePlayback.Mmd mmd) position=mmd.value().pose().bones().get(bone.index()).position();
                else if(details instanceof ScenePackagePlayback.Fbx fbx) {var matrix=fbx.value().nodes().get(bone.index()).world();position=new Vec3((float)matrix.get(9),(float)matrix.get(10),(float)matrix.get(11));}
                else {
                    var p=details instanceof ScenePackagePlayback.Scene s?s.pose():details instanceof ScenePackagePlayback.Vrm v?v.value().pose():null;
                    if(p!=null && bone.index()<p.globalMatrices().size())position=p.globalMatrices().get(bone.index()).transformPoint(Vec3.ZERO);
                }
                var point=transform.transform(new org.joml.Vector4f(position.x(),position.y(),position.z(),1));
                if(Math.abs(point.w)>1e-8)points.add(new GeneralMeshInstance.BonePoint(bone.index(),bone.parent(),
                    (point.x/point.w+1)*window.getGuiScaledWidth()*.5f,(1-point.y/point.w)*window.getGuiScaledHeight()*.5f));
            }
            instance.projectedBones=java.util.List.copyOf(points);
        }
        entity.countRender();return true;
    }

    private static int renderHeldItems(CustomEntity<?> entity,GeneralMeshInstance instance,PoseStack pose,MultiBufferSource buffers,int light,boolean firstPerson,Matrix4f placement) {
        if(!(entity.getEntity() instanceof net.minecraft.world.entity.LivingEntity living))return 0;
        var held=instance.profile().heldItems();
        if(!held.enabled())return 0;
        if(firstPerson&&held.firstPerson()==SceneModelProfile.FirstPersonItems.HIDDEN)return 3;
        if(firstPerson&&held.firstPerson()!=SceneModelProfile.FirstPersonItems.MODEL)return 0;
        int rendered=0;
        for(boolean left:new boolean[]{false,true}) {
            boolean main=left==(living.getMainArm()==net.minecraft.world.entity.HumanoidArm.LEFT);
            var item=main?living.getMainHandItem():living.getOffhandItem();
            var itemContext=com.elfmcys.ysm.client.animation.GeneralItemContext.of(living,left,firstPerson);
            var attachment=instance.profile().features().attachment(instance.profile(),itemContext);
            var parts=new java.util.ArrayList<SceneModelFeatures.ItemPart>();parts.add(new SceneModelFeatures.ItemPart(attachment.socket(),attachment.display(),attachment.renderer()));parts.addAll(attachment.parts());
            for(var part:parts){pose.pushPose();
            try {
                var socket=instance.handSocket(part.socket());if(socket.isEmpty())continue;
                if(item.isEmpty()||part.display()==SceneModelFeatures.Display.NONE){rendered|=left?1:2;continue;}
                pose.scale(entity.getWidthScale(),entity.getHeightScale(),entity.getWidthScale());
                pose.mulPose(com.mojang.math.Axis.YP.rotation((float)Math.PI));
                var transform=new Matrix4f(placement).mul(new Matrix4f().set(socket.get().copy()));
                pose.mulPoseMatrix(transform);pose.last().normal().mul(new org.joml.Matrix3f(transform).invert().transpose());
                var extension=new com.elfmcys.ysm.api.rendering.v0.event.RenderSceneItemEvent(living,item,itemContext,part.renderer(),pose,buffers,light);
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(extension);
                if(extension.result()!=com.elfmcys.ysm.api.rendering.v0.event.RenderSceneItemEvent.Result.PASS){rendered|=left?1:2;continue;}
                var display=switch(part.display()) {
                    case FIXED -> net.minecraft.world.item.ItemDisplayContext.FIXED;
                    case FIRST_PERSON -> left?net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND:net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
                    case INHERIT -> firstPerson?(left?net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND:net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND):(left?net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND:net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND);
                    default -> left?net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND:net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
                };
                com.elfmcys.ysm.client.compat.tacz.TACZCompat.openFlashShellRender(living,item);
                try {net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getItemInHandRenderer().renderItem(living,item,display,left,pose,buffers,light);}
                finally {com.elfmcys.ysm.client.compat.tacz.TACZCompat.stopFlashShellRender(item);}
                rendered|=left?1:2;
            } catch(RuntimeException failure){instance.itemFailure(failure);} finally {pose.popPose();}}
        }
        return rendered;
    }

    /** Explicit unit and handedness conversion, applied after the source's animation/physics in its own coordinates. */
    static Matrix4f sourceToMeters(SceneAsset.Coordinates coordinates) {
        float scale=(float)coordinates.metersPerUnit();
        if(!Float.isFinite(scale)||scale<=0) throw new IllegalArgumentException("Scene scale cannot be represented by the graphics host");
        var transform=new Matrix4f().scaling(scale,scale,coordinates.rightHanded()?scale:-scale);
        return switch(coordinates.upAxis()) {
            case "Y" -> transform;
            case "Z" -> transform.rotateX(-(float)Math.PI/2);
            case "X" -> transform.rotateZ((float)Math.PI/2);
            default -> throw new IllegalArgumentException("Scene requires an explicit source-axis adapter: "+coordinates.upAxis());
        };
    }
}
