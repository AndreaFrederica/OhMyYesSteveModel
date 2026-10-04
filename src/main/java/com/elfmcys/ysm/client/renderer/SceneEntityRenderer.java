package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.api.rendering.v0.SceneView;
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
                RenderUtil.extractRenderContext().firstPersonMod());
    }

    /**
     * Renders a scene with an explicit view selection.  The normal entity path
     * derives this from the active compatibility adapter, while the vanilla
     * RenderArmEvent path has no such adapter and must explicitly request the
     * first-person geometry.
     */
    public static boolean render(CustomEntity<?> entity,GeneralMeshInstance instance,PoseStack pose,
                              MultiBufferSource buffers,int packedLight, boolean firstPerson) {
        var context=RenderUtil.extractRenderContext();
        if (context.firstPersonMod() != firstPerson) {
            context = new RenderContext(context.level(), context.irisShadow(), firstPerson,
                    context.inventory(), context.paperDoll(), context.offScreen(), context.immutable());
        }
        var frame = firstPerson ? instance.frame().firstPerson() : instance.frame().thirdPerson();
        var modelView=new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose())
                .scale(entity.getWidthScale(),entity.getHeightScale(),entity.getWidthScale());
        // EntityRenderer's canonical front is -Z; glTF's canonical avatar front after handedness conversion is +Z.
        modelView.rotateY((float)Math.PI).mul(instance.placement()).mul(sourceToMeters(frame.coordinates()));
        var projection=RenderSystem.getProjectionMatrix();
        float brightness=Math.max(LightTexture.block(packedLight),LightTexture.sky(packedLight))/15f;
        // Neutral host shading; source light/shadow tracks remain exposed through RenderSceneEvent.
        var view=new SceneView(new Matrix4(modelView.get(new float[16])),new Matrix4(projection.get(new float[16])),
                new Vec3(.3f,.7f,1),new Vec3(brightness,brightness,brightness),
                new Vec3(.2f*brightness,.2f*brightness,.2f*brightness),new FloatData(1,1,1,1),projection.m33()!=0);
        instance.renderedHeldItems=0;
        if(!ClientModelService.instance().sceneRenderer().render(instance,entity.getEntity(),entity.renderTargetKind(),
                context,firstPerson,view,buffers))return false;
        instance.renderedHeldItems=renderHeldItems(entity,instance,pose,buffers,packedLight,firstPerson);
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

    private static int renderHeldItems(CustomEntity<?> entity,GeneralMeshInstance instance,PoseStack pose,MultiBufferSource buffers,int light,boolean firstPerson) {
        if(!(entity.getEntity() instanceof net.minecraft.world.entity.LivingEntity living))return 0;
        var held=instance.profile().heldItems();
        if(!held.enabled())return 0;
        if(firstPerson&&held.firstPerson()==SceneModelProfile.FirstPersonItems.HIDDEN)return 3;
        if(firstPerson&&held.firstPerson()!=SceneModelProfile.FirstPersonItems.MODEL)return 0;
        int rendered=0;
        for(boolean left:new boolean[]{false,true}) {
            boolean main=left==(living.getMainArm()==net.minecraft.world.entity.HumanoidArm.LEFT);
            var item=main?living.getMainHandItem():living.getOffhandItem();
            pose.pushPose();
            try {
                var socket=SceneHandSockets.resolve(instance.frame(),instance.profile(),left);if(socket.isEmpty())continue;
                if(item.isEmpty()){rendered|=left?1:2;continue;}
                pose.scale(entity.getWidthScale(),entity.getHeightScale(),entity.getWidthScale());
                pose.mulPose(com.mojang.math.Axis.YP.rotation((float)Math.PI));
                // mulPoseMatrix does not update normals. Install the full inverse-transpose explicitly.
                var transform=new Matrix4f(instance.placement()).mul(new Matrix4f().set(socket.get().copy()));
                pose.mulPoseMatrix(transform);
                pose.last().normal().mul(new org.joml.Matrix3f(transform).invert().transpose());
                var display=left?net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND:net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
                net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().getItemInHandRenderer()
                    .renderItem(living,item,display,left,pose,buffers,light);
                rendered|=left?1:2;
            } catch(RuntimeException failure){instance.itemFailure(failure);} finally {pose.popPose();}
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
