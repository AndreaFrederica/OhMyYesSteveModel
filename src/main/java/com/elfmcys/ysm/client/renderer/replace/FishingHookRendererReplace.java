package com.elfmcys.ysm.client.renderer.replace;

import com.elfmcys.ysm.capability.ProjectileAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.util.PersonView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Unique;

public class FishingHookRendererReplace {
    public static boolean renderInMixin(EntityFishHook entity, double x, double y, double z, float yaw, float partialTick) {
        var cap = entity.getCapability(ProjectileAnimatableCapabilityProvider.CAP, null);
        if (cap != null) {
            if (cap.isInitialized() && cap.isModelPresent()) {
                // 鱼漂会上下乱串，这里强制归0
                entity.rotationPitch = 0;
                entity.prevRotationPitch = 0;

                // 渲染鱼漂
                RegisterEntityRenderersEvent.getProjectRenderer().doRender(entity, x, y, z, yaw, partialTick);

                // 渲染鱼线
                EntityPlayer player = entity.getAngler();
                //noinspection ConstantValue
                if (player != null) {
                    renderFishingLine(entity, x, y, z, partialTick, player);
                }
                return false;
            }
        }
        return true;
    }

    private static void renderFishingLine(EntityFishHook hook, double x, double y, double z, float partialTicks, EntityPlayer player) {
        // 确定钓竿持握方向
        int handDirection = player.getPrimaryHand() == EnumHandSide.RIGHT ? 1 : -1;
        ItemStack mainHandItem = player.getHeldItemMainhand();
        if (!(mainHandItem.getItem() instanceof ItemFishingRod)) {
            handDirection = -handDirection;
        }

        // 计算玩家动画相关参数
        float attackAnimProgress = player.getSwingProgress(partialTicks);
        float swingAnim = MathHelper.sin(MathHelper.sqrt(attackAnimProgress) * (float) Math.PI);
        float bodyRot = (player.prevRenderYawOffset + (player.renderYawOffset - player.prevRenderYawOffset) * partialTicks) * ((float) Math.PI / 180F);
        double bodyRotSin = MathHelper.sin(bodyRot);
        double bodyRotCos = MathHelper.cos(bodyRot);
        double handOffset = handDirection * 0.35;
        double armDistance = 0.8;

        // 计算玩家钓竿位置
        double playerX, playerY, playerZ;
        float eyeHeightOffset;

        // RenderFish
        var mc = Minecraft.getMinecraft();
        if ((mc.gameSettings != null && mc.gameSettings.thirdPersonView <= PersonView.FIRST_PERSON) && player == mc.player) {
            // 第一人称视角计算
            // 1.12.2 的有点诡异，真的对吗？
            float fovScale = mc.gameSettings.fovSetting;
            fovScale /= 100.0F;
            Vec3d cameraOffset = new Vec3d(handDirection * -0.36 * fovScale, -0.045 * fovScale, 0.4);
            cameraOffset = cameraOffset.rotatePitch(-(player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * partialTicks) * ((float) Math.PI / 180F));
            cameraOffset = cameraOffset.rotateYaw(-(player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks) * ((float) Math.PI / 180F));
            cameraOffset = cameraOffset.rotateYaw(swingAnim * 0.5F);
            cameraOffset = cameraOffset.rotatePitch(-swingAnim * 0.7F);

            playerX = player.prevPosX + (player.posX - player.prevPosX) * partialTicks + cameraOffset.x;
            playerY = player.prevPosY + (player.posY - player.prevPosY) * partialTicks + cameraOffset.y;
            playerZ = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks + cameraOffset.z;
            eyeHeightOffset = player.getEyeHeight();
        } else {
            // 第三人称视角计算
            playerX = player.prevPosX + (player.posX - player.prevPosX) * partialTicks - bodyRotCos * handOffset - bodyRotSin * armDistance;
            playerY = player.prevPosY + player.getEyeHeight() + (player.posY - player.prevPosY) * partialTicks - 0.45;
            playerZ = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks - bodyRotSin * handOffset + bodyRotCos * armDistance;
            eyeHeightOffset = player.isSneaking() ? -0.1875F : 0.0F;
        }

        // 计算鱼钩位置
        double hookX = hook.prevPosX + (hook.posX - hook.prevPosX) * partialTicks;
        double hookY = hook.prevPosY + (hook.posY - hook.prevPosY) * partialTicks + 0.25;
        double hookZ = hook.prevPosZ + (hook.posZ - hook.prevPosZ) * partialTicks;

        // 计算钓线向量
        float lineX = (float) (playerX - hookX);
        float lineY = (float) (playerY - hookY) + eyeHeightOffset;
        float lineZ = (float) (playerZ - hookZ);

        // 获取钓线颜色
        float[] color = getLineColor(hook);

        // 渲染钓线
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= 16; ++i) {
            float f = fraction(i);
            buffer.pos(x + lineX * f, y + lineY * (f * f + f) * 0.5F + 0.25F, z + lineZ * f)
                    .color(color[0], color[1], color[2], 1.0F)
                    .endVertex();
        }
        tessellator.draw();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
    }

    @Unique
    private static float[] getLineColor(EntityFishHook fishingHook) {
        return new float[]{0f, 0f, 0f};
    }

    @Unique
    private static float fraction(int numerator) {
        return (float) numerator / (float) 16;
    }
}
