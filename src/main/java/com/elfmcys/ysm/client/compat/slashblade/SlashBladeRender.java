package com.elfmcys.ysm.client.compat.slashblade;

import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import mods.flammpfeil.slashblade.client.model.BladeModelManager;
import mods.flammpfeil.slashblade.client.model.obj.WavefrontObject;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.util.ResourceLocationRaw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

// FIXME：当前为 Agent 迁移，需人工重新对照 SlashBlade 语义迁移
@SideOnly(Side.CLIENT)
public class SlashBladeRender {
    private static final ResourceLocationRaw RESOURCE_DEFAULT_MODEL = new ResourceLocationRaw("flammpfeil.slashblade", "model/blade.obj");
    private static final ResourceLocationRaw RESOURCE_DEFAULT_TEXTURE = new ResourceLocationRaw("flammpfeil.slashblade", "model/blade.png");

    public static void renderSlashBlade(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemSlashBlade blade = (ItemSlashBlade) stack.getItem();
        ResourceLocationRaw texture = texture(blade, stack);
        WavefrontObject obj = model(blade, stack);
        String part = blade.getSwordType(stack).contains(ItemSlashBlade.SwordType.Broken) ? "blade_damaged" : "blade";
        GlStateManager.pushMatrix();
        renderPart(obj, part, texture);
        renderPartLuminous(obj, part + "_luminous", texture);
        renderPart(obj, "sheath", texture);
        renderPartLuminous(obj, "sheath_luminous", texture);
        GlStateManager.popMatrix();
    }

    public static void renderMainhandSlashBlade(EntityLivingBase livingEntity, GeoRenderData data, ItemStack stack) {
        if (SlashBladeCompat.isSlashBladeItem(stack)) {
            // 如果没有 bladeBones 和 sheathBones，说明是旧版渲染
            if (data.modelState.locatorGroupSize(PlayerLocator.get().blade) == 0 ||
                data.modelState.locatorGroupSize(PlayerLocator.get().sheath) == 0 ||
                data.modelState.locatorGroupSize(PlayerLocator.get().leftWaist) == 0) {
                oldMainhandSlashBlade(livingEntity, data, stack);
            } else {
                newMainhandSlashBlade(data, stack);
            }
        }
    }

    private static void newMainhandSlashBlade(GeoRenderData data, ItemStack stack) {
        ItemSlashBlade blade = (ItemSlashBlade) stack.getItem();
        ResourceLocationRaw texture = texture(blade, stack);
        WavefrontObject obj = model(blade, stack);
        String part = blade.getSwordType(stack).contains(ItemSlashBlade.SwordType.Broken) ? "blade_damaged" : "blade";

        // 定位点定在刀中心，刀朝向前方（默认）
        data.modelState.visitLocatorGroup(PlayerLocator.get().leftWaist, () -> {
            GlStateManager.translate(0, 0.025, -0.6);
            GlStateManager.scale(0.01F, 0.01F, 0.01F);
            GlStateManager.rotate(-90, 0, 1, 0);
            GlStateManager.rotate(180, 0, 0, 1);

            renderPart(obj, part, texture);
            renderPartLuminous(obj, part + "_luminous", texture);
            renderPart(obj, "sheath", texture);
            renderPartLuminous(obj, "sheath_luminous", texture);
        });

        // 定位点定在刀柄中心，刀朝向前方（默认）
        data.modelState.visitLocatorGroup(PlayerLocator.get().blade, () -> {
            GlStateManager.translate(0, 0.035, 0);
            GlStateManager.scale(0.01F, 0.01F, 0.01F);
            GlStateManager.rotate(-90, 0, 1, 0);
            GlStateManager.rotate(180, 1, 0, 0);

            renderPart(obj, part, texture);
            renderPartLuminous(obj, part + "_luminous", texture);
        });

        // 定位点定在刀鞘最末端，刀朝向前方（默认）
        data.modelState.visitLocatorGroup(PlayerLocator.get().sheath, () -> {
            GlStateManager.translate(0, 0.025, -0.6);
            GlStateManager.scale(0.01F, 0.01F, 0.01F);
            GlStateManager.rotate(-90, 0, 1, 0);
            GlStateManager.rotate(180, 0, 0, 1);

            renderPart(obj, "sheath", texture);
            renderPartLuminous(obj, "sheath_luminous", texture);
        });
    }

    private static void oldMainhandSlashBlade(EntityLivingBase livingEntity, GeoRenderData data, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }

        ItemSlashBlade blade = (ItemSlashBlade) stack.getItem();
        ResourceLocationRaw texture = texture(blade, stack);
        WavefrontObject obj = model(blade, stack);
        String part = blade.getSwordType(stack).contains(ItemSlashBlade.SwordType.Broken) ? "blade_damaged" : "blade";

        // 主手的刀渲染在左边
        data.modelState.visitLocatorGroupOrDefault(PlayerLocator.get().leftWaist, () -> {
            GlStateManager.translate(0, 0, -0.7);
            GlStateManager.scale(0.01F, 0.01F, 0.01F);
            GlStateManager.rotate(-90, 0, 1, 0);
            GlStateManager.rotate(180, 0, 0, 1);

            renderPart(obj, "sheath", texture);
            renderPartLuminous(obj, "sheath_luminous", texture);
            long time = livingEntity.world.getTotalWorldTime() - ItemSlashBlade.LastActionTime.get(ItemSlashBlade.getItemTagCompound(stack));
            if (time < 5) {
                float i = time + data.partialTicks;
                GlStateManager.translate(0, 0, -0.5 / 0.007);
                GlStateManager.rotate(60 + i * 48, 0, 1, 0);
                GlStateManager.rotate(90, 1, 0, 0);
            }
            renderPart(obj, part, texture);
            renderPartLuminous(obj, part + "_luminous", texture);
        }, () -> {
            GlStateManager.translate(-0.25, 1.25, 0);
            GlStateManager.rotate(20, 1, 0, 0);
        });
    }

    public static void renderOffhandSlashBlade(GeoRenderData data, ItemStack stack) {
        if (SlashBladeCompat.isSlashBladeItem(stack)) {
            // 副手的刀渲染在右边
            data.modelState.visitLocatorGroupOrDefault(PlayerLocator.get().rightWaist, () -> {
                GlStateManager.translate(0, 0, -0.7);
                GlStateManager.scale(0.01F, 0.01F, 0.01F);
                GlStateManager.rotate(-90, 0, 1, 0);
                GlStateManager.rotate(180, 0, 0, 1);
                SlashBladeRender.renderSlashBlade(stack);
            }, () -> {
                GlStateManager.translate(0.25, 1.25, 0);
                GlStateManager.rotate(5, 1, 0, 0);
            });
        }
    }

    private static ResourceLocationRaw texture(ItemSlashBlade blade, ItemStack stack) {
        ResourceLocationRaw texture = blade.getModelTexture(stack);
        return texture == null ? RESOURCE_DEFAULT_TEXTURE : texture;
    }

    private static WavefrontObject model(ItemSlashBlade blade, ItemStack stack) {
        ResourceLocationRaw modelLoc = blade.getModelLocation(stack);
        return BladeModelManager.getInstance().getModel(modelLoc == null ? RESOURCE_DEFAULT_MODEL : modelLoc);
    }

    /**
     * 对应 BladeSpecialRender.renderNaked 的普通 pass：
     * bindTexture + NEAREST 过滤 + alphaFunc(GREATER, 0.05) + renderPart。
     */
    private static void renderPart(WavefrontObject obj, String part, ResourceLocationRaw texture) {
        bindTexture(texture);
        GlStateManager.disableCull();
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.05F);
        obj.renderPart(part);
    }

    /**
     * 对应 BladeSpecialRender.renderNaked 的发光 pass：
     * disableLighting + enableBlend + glBlendFunc(770,1) + lightmap(240,240) + renderPart + 恢复 lightmap
     * + glEnable(2896) + OpenGlHelper.glBlendFunc(770,771,1,0)。
     */
    private static void renderPartLuminous(WavefrontObject obj, String part, ResourceLocationRaw texture) {
        bindTexture(texture);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        float lastx = OpenGlHelper.lastBrightnessX;
        float lasty = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        obj.renderPart(part);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastx, lasty);
        GL11.glEnable(GL11.GL_LIGHTING);
        OpenGlHelper.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
    }

    private static void bindTexture(ResourceLocationRaw texture) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
    }
}
