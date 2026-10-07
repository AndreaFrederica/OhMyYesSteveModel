package com.elfmcys.ysm.util;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.compat.FirstPersonCompat;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.client.renderer.replace.EntityRendererReplace;
import com.elfmcys.ysm.geckolib3.geo.GeoReplacedEntityRenderer;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.mclib.utils.Interpolations;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ExecutionException;

public final class RenderUtil {
    private static boolean renderingInInventory = false;
    private static boolean renderingInPaperDoll = false;
    private static boolean renderingLevel = false;

    public static void setRenderingInInventory(boolean value) {
        renderingInInventory = value;
    }

    public static void setRenderingInPaperDoll(boolean renderingEntitiesInPaperDoll) {
        RenderUtil.renderingInPaperDoll = renderingEntitiesInPaperDoll;
    }

    public static void setRenderingLevel(boolean renderingLevel) {
        RenderUtil.renderingLevel = renderingLevel;
    }

    public static boolean isRenderingLevel() {
        assertOnMinecraftThread();
        return renderingLevel;
    }

    public static RenderContext extractRenderContext() {
        assertOnMinecraftThread();
        return new RenderContext(
                renderingLevel && !FirstPersonCompat.isRenderingPlayer(),
                false,
                FirstPersonCompat.isRenderingPlayer(),
                renderingInInventory,
                renderingInPaperDoll,
                false,
                false);
    }

    public static void assertOnMinecraftThread() {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Render called from wrong thread");
        }
    }

    public static void adjustPassengerPosition(Entity entity, float partialTicks) {
        Entity vehicle = entity.getRidingEntity();
        if (vehicle != null) {
            var vehicleCap = vehicle.getCapability(VehicleAnimatableCapabilityProvider.CAP, null);
            if (vehicleCap != null) {
                if (!vehicleCap.isInitialized() || !vehicleCap.isModelPresent()) {
                    return;
                }
                int index = vehicle.getPassengers().indexOf(entity);
                if (index < 0) {
                    return;
                }
                AnimatedGeoModel loadedGeoModel = vehicleCap.getLoadedGeoModel();
                // TODO
//                if (loadedGeoModel == null || loadedGeoModel.passengerBones().isEmpty() || index >= loadedGeoModel.passengerBones().size()) {
//                    return;
//                }
//                var bone = loadedGeoModel.passengerBones().get(index);
//                if (bone == null) {
//                    return;
//                }
                float rawVehicleYaw = Interpolations.lerp(vehicle.prevRotationYaw, vehicle.rotationYaw, partialTicks);
                float vehicleYaw = EntityRendererReplace.getYaw(vehicle, rawVehicleYaw, partialTicks);
                GlStateManager.rotate(180 - vehicleYaw, 0, 1, 0);
                // TODO
                // RenderUtils.prepMatrixForLocator(bone);
                GlStateManager.rotate(180 - vehicleYaw, 0, -1, 0);

                double yOffset = -vehicle.getMountedYOffset() - entity.getYOffset();
                // 如果乘客带有玩家 cap 或者女仆 cap，那么不扣除 0.5 偏移
                boolean playerHasCap = entity instanceof EntityPlayer && entity.getCapability(PlayerAnimatableCapabilityProvider.CAP, null) != null;
                if (playerHasCap || TlmClientCompat.hasMaidCap(entity)) {
                    yOffset = yOffset - 0.5;
                }
                GlStateManager.translate(0, yOffset, 0);
            }
        }
    }

    public static <T extends EntityLivingBase, TAnimatable extends AnimatableEntity<T> & IPreviewEntity> void renderTextureScreenEntity(float pPosX, float pPosY, float pScale, float pitch, float yaw, float partialTicks, TAnimatable entity, GeoReplacedEntityRenderer<T, ? super TAnimatable> renderer, boolean showGround) {
        setRenderingInInventory(true);
        var living = entity.getEntity();

        GlStateManager.enableColorMaterial();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.pushMatrix();
        GlStateManager.translate(pPosX, pPosY, 1250.0D);
        GlStateManager.scale(1.0F, 1.0F, -1.0F);

        GlStateManager.translate(0.0D, 0.0D, 1000.0D);
        GlStateManager.scale(pScale, pScale, pScale);
        GlStateManager.translate(0, 0.8, 0);
        GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(-10 + pitch, 1.0F, 0.0F, 0.0F);

        float renderYawOffset = living.renderYawOffset;
        float prevRenderYawOffset = living.prevRenderYawOffset;
        float rotationYaw = living.rotationYaw;
        float prevRotationYaw = living.prevRotationYaw;
        float rotationPitch = living.rotationPitch;
        float prevRotationPitch = living.prevRotationPitch;
        float prevRotationYawHead = living.prevRotationYawHead;
        float rotationYawHead = living.rotationYawHead;
        Entity vehicle = living.ridingEntity;

        boolean sleeping = false;
        BlockPos bedLocation = null;
        float renderOffsetX = 0;
        float renderOffsetY = 0;
        float renderOffsetZ = 0;
        if (living instanceof EntityPlayer player) {
            sleeping = player.sleeping;
            bedLocation = player.bedLocation;
            renderOffsetX = player.renderOffsetX;
            renderOffsetY = player.renderOffsetY;
            renderOffsetZ = player.renderOffsetZ;
        }

        living.renderYawOffset = -yaw;
        living.prevRenderYawOffset = -yaw;
        living.rotationYaw = 180;
        living.prevRotationYaw = 180;
        living.rotationPitch = 0;
        living.prevRotationPitch = 0;
        living.rotationYawHead = -yaw;
        living.prevRotationYawHead = -yaw;
        // 渲染器会根据 ridingEntity 覆写 yaw，这里需要临时屏蔽
        // FIXME：现代 YSM 需要这个吗？
        living.ridingEntity = null;

        GlStateManager.pushMatrix(); // Entity stack
        RenderHelper.enableStandardItemLighting();
        RenderManager dispatcher = Minecraft.getMinecraft().getRenderManager();
        dispatcher.setPlayerViewY(180.0F);
        dispatcher.setRenderShadow(false);
        var guiAnim = entity.getPreviewInfo();
        if (guiAnim.hasPreview("sleep")) {
            GlStateManager.rotate(yaw - 90, 0, 1, 0);
            GlStateManager.translate(0.5, 0.5625, 0);
            if (living instanceof EntityPlayer player) {
                player.sleeping = true;
                // 原版的床睡完后不会重置这几个状态，而它们在 sleeping 为 true 时会被 applyRotation 应用
                player.bedLocation = null;
                player.renderOffsetX = 0;
                player.renderOffsetY = 0;
                player.renderOffsetZ = 0;
            }
        }
        // FIXME：可能需要临时写入 pose
        if (guiAnim.hasPreview("swim") || guiAnim.hasPreview("swim_stand")) {
            //living.setPose(Pose.SWIMMING);
        }
        if (guiAnim.hasPreview("sneak") || guiAnim.hasPreview("sneaking")) {
            //living.setPose(Pose.CROUCHING);
        }
        if (guiAnim.hasPreview("sit")) {
            GlStateManager.translate(0, -0.5, 0);
        }
        if (guiAnim.hasPreview("ride")) {
            GlStateManager.translate(0, 0.85, 0);
        }
        if (guiAnim.hasPreview("ride_pig")) {
            GlStateManager.translate(0, 0.3125, 0);
        }
        if (guiAnim.hasPreview("boat")) {
            GlStateManager.translate(0, -0.45, 0);
        }
        try {
            renderExtraEntity(yaw, entity, partialTicks, dispatcher);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
        renderer.renderAnimatableEntity(entity, 0, 0, 0, 0, partialTicks);
        dispatcher.setRenderShadow(true);

        GlStateManager.popMatrix(); // Entity stack
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableColorMaterial();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        if (guiAnim.hasPreview("sleep")) {
            renderBed(yaw);
        }
        if (showGround) {
            renderGround(yaw);
        }

        if (living instanceof EntityPlayer player) {
            player.sleeping = sleeping;
            player.bedLocation = bedLocation;
            player.renderOffsetX = renderOffsetX;
            player.renderOffsetY = renderOffsetY;
            player.renderOffsetZ = renderOffsetZ;
        }

        living.renderYawOffset = renderYawOffset;
        living.prevRenderYawOffset = prevRenderYawOffset;
        living.rotationYaw = rotationYaw;
        living.prevRotationYaw = prevRotationYaw;
        living.rotationPitch = rotationPitch;
        living.prevRotationPitch = prevRotationPitch;
        living.prevRotationYawHead = prevRotationYawHead;
        living.rotationYawHead = rotationYawHead;
        living.ridingEntity = vehicle;

        GlStateManager.popMatrix();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        setRenderingInInventory(false);
    }

    private static void renderBed(float yaw) {
        GlStateManager.pushMatrix();
        GlStateManager.rotate(yaw + 180, 0, 1, 0);
        GlStateManager.translate(-0.5, 0, 0.5);
        ItemStack stack = new ItemStack(Items.BED, 1, EnumDyeColor.RED.getMetadata());
        stack.getItem().getTileEntityItemStackRenderer().renderByItem(stack);
        GlStateManager.popMatrix();
    }

    // FIXME：状态泄露，世界光照影响 GUI 光照
    private static void renderGround(float yaw) {
        GlStateManager.pushMatrix();
        GlStateManager.rotate(yaw, 0, 1, 0);
        GlStateManager.translate(-1.5, -1, -1.5);
        Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                GlStateManager.translate(0, 0, 1);
                renderSingleBlock(Blocks.GRASS.getDefaultState());
            }
            GlStateManager.translate(1, 0, -3);
        }
        GlStateManager.translate(-1, 1, 1);
        renderSingleBlock(Blocks.TALLGRASS.getDefaultState().withProperty(BlockTallGrass.TYPE, BlockTallGrass.EnumType.GRASS));
        GlStateManager.translate(0, 0, 1);
        renderSingleBlock(Blocks.RED_FLOWER.getDefaultState().withProperty(Blocks.RED_FLOWER.getTypeProperty(), BlockFlower.EnumFlowerType.RED_TULIP));
        GlStateManager.popMatrix();
    }

    private static void renderSingleBlock(IBlockState state) {
        GlStateManager.pushMatrix(); // 一定要 push，原版方块渲染不干净
        Minecraft.getMinecraft().getBlockRendererDispatcher().renderBlockBrightness(state, 1.0F);
        GlStateManager.popMatrix();
    }

    @SuppressWarnings({"DataFlowIssue", "UnnecessaryReturnStatement"})
    private static <TAnimatable extends AnimatableEntity<?> & IPreviewEntity> void renderExtraEntity(float yaw, TAnimatable animatableEntity, float partialTicks, RenderManager dispatcher) throws ExecutionException {
        var player = animatableEntity.getEntity();
        var guiAnim = animatableEntity.getPreviewInfo();

        if (guiAnim.hasPreview("ride")) {
            Entity entity = AnimatableCacheUtil.ENTITIES_CACHE.get(EntityList.getKey(EntityHorse.class), () -> new EntityHorse(player.world));
            renderExtraEntity(yaw, player, dispatcher, entity, partialTicks);
            return;
        }
        if (guiAnim.hasPreview("ride_pig")) {
            Entity entity = AnimatableCacheUtil.ENTITIES_CACHE.get(EntityList.getKey(EntityPig.class), () -> new EntityPig(player.world));
            renderExtraEntity(yaw, player, dispatcher, entity, partialTicks);
            return;
        }
        if (guiAnim.hasPreview("boat")) {
            Entity entity = AnimatableCacheUtil.ENTITIES_CACHE.get(EntityList.getKey(EntityBoat.class), () -> new EntityBoat(player.world));
            renderExtraEntity(yaw, player, dispatcher, entity, partialTicks);
            return;
        }
    }

    private static void renderExtraEntity(float yaw, Entity player, RenderManager dispatcher, Entity entity, float partialTicks) {
        GlStateManager.pushMatrix();
        GlStateManager.rotate(yaw, 0, 1, 0);
        dispatcher.renderEntity(entity, 0, -entity.getMountedYOffset() - player.getYOffset(), 0, 0, partialTicks, false);
        GlStateManager.popMatrix();
    }

    public static <T extends EntityLivingBase, TAnimatable extends CustomHumanoidEntity<T>> void renderModelInGui(
            float pPosX,
            float pPosY,
            float pScale,
            float partialTicks,
            TAnimatable animatableEntity,
            GeoReplacedEntityRenderer<T, TAnimatable> renderer,
            boolean disablePreviewRotation,
            boolean disableEquipments) {
        setRenderingInInventory(true);
        try {
            renderModelInGuiStateful(pPosX, pPosY, pScale, partialTicks,
                    animatableEntity, renderer, disablePreviewRotation, disableEquipments);
        } finally {
            setRenderingInInventory(false);
        }
    }

    private static <T extends EntityLivingBase,
            TAnimatable extends CustomHumanoidEntity<T>> void renderModelInGuiStateful(
            float pPosX,
            float pPosY,
            float pScale,
            float partialTicks,
            TAnimatable animatableEntity,
            GeoReplacedEntityRenderer<T, TAnimatable> renderer,
            boolean disablePreviewRotation,
            boolean disableEquipments) {
        var living = animatableEntity.getEntity();

        float renderYawOffset = living.renderYawOffset;
        float prevRenderYawOffset = living.prevRenderYawOffset;
        float rotationYaw = living.rotationYaw;
        float prevRotationYaw = living.prevRotationYaw;
        float rotationPitch = living.rotationPitch;
        float prevRotationPitch = living.prevRotationPitch;
        float prevRotationYawHead = living.prevRotationYawHead;
        float rotationYawHead = living.rotationYawHead;

        ItemStack[] itemStacks = null;
        RenderManager dispatcher = null;
        boolean viewPushed = false;
        boolean lightingEnabled = false;
        boolean shadowDisabled = false;
        GlStateManager.enableColorMaterial();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            GlStateManager.pushMatrix();
            viewPushed = true;
            GlStateManager.translate(pPosX, pPosY, 1050.0D);
            GlStateManager.scale(1.0F, 1.0F, -1.0F);

            GlStateManager.translate(0.0D, disablePreviewRotation ? 5.5 : 0, 1000.0D);
            GlStateManager.scale(pScale, pScale, pScale);
            GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
            GlStateManager.rotate(disablePreviewRotation ? 0 : -10, 1.0F, 0.0F, 0.0F);

        if (disableEquipments && living instanceof EntityPlayer player) {
            itemStacks = new ItemStack[EntityEquipmentSlot.values().length];
            int i = 0;
            for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
                if (slot == EntityEquipmentSlot.MAINHAND) {
                    itemStacks[i] = player.getItemStackFromSlot(slot);
                    player.inventory.mainInventory.set(player.inventory.currentItem, ItemStack.EMPTY);
                } else if (slot == EntityEquipmentSlot.OFFHAND) {
                    itemStacks[i] = player.getItemStackFromSlot(slot);
                    player.inventory.offHandInventory.set(0, ItemStack.EMPTY);
                } else {
                    var armor = player.inventory.armorInventory;
                    if (armor.size() <= slot.getSlotIndex()) {
                        continue;
                    }
                    itemStacks[i] = player.getItemStackFromSlot(slot);
                    armor.set(slot.getSlotIndex(), ItemStack.EMPTY);
                }
                i++;
            }
        } else {
            itemStacks = null;
        }

        float yRotGui = disablePreviewRotation ? 180 : 200;
        living.renderYawOffset = yRotGui;
        living.prevRenderYawOffset = yRotGui;
        living.rotationYaw = yRotGui;
        living.prevRotationYaw = yRotGui;
        living.rotationPitch = 0;
        living.prevRotationPitch = 0;
        living.rotationYawHead = living.rotationYaw;
        living.prevRotationYawHead = living.rotationYaw;

        // 修正骑乘时 GUI 界面歪头的 bug
        if (living.getRidingEntity() instanceof EntityLivingBase vehicle) {
            float vehicleYRot = vehicle.rotationYaw;
            GlStateManager.rotate(vehicleYRot - yRotGui, 0, 1, 0);
            living.rotationYawHead = vehicleYRot;
            living.prevRotationYawHead = vehicleYRot;
        }

        RenderHelper.enableStandardItemLighting();
        lightingEnabled = true;
        dispatcher = Minecraft.getMinecraft().getRenderManager();
        dispatcher.setPlayerViewY(180.0F);
        dispatcher.setRenderShadow(false);
        shadowDisabled = true;
        renderer.renderAnimatableEntity(animatableEntity, 0, 0, 0, 0, partialTicks);
        } finally {
            try {
                if (shadowDisabled) {
                    dispatcher.setRenderShadow(true);
                }
            } finally {
                living.renderYawOffset = renderYawOffset;
                living.prevRenderYawOffset = prevRenderYawOffset;
                living.rotationYaw = rotationYaw;
                living.prevRotationYaw = prevRotationYaw;
                living.rotationPitch = rotationPitch;
                living.prevRotationPitch = prevRotationPitch;
                living.prevRotationYawHead = prevRotationYawHead;
                living.rotationYawHead = rotationYawHead;
                try {
                    if (itemStacks != null) {
                        EntityPlayer player = (EntityPlayer) living;
                        int i = 0;
                        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
                            ItemStack itemStack = itemStacks[i];
                            if (slot == EntityEquipmentSlot.MAINHAND) {
                                player.inventory.mainInventory.set(player.inventory.currentItem, itemStack);
                            } else if (slot == EntityEquipmentSlot.OFFHAND) {
                                player.inventory.offHandInventory.set(0, itemStack);
                            } else {
                                var armor = player.inventory.armorInventory;
                                if (armor.size() <= slot.getSlotIndex()) {
                                    continue;
                                }
                                armor.set(slot.getSlotIndex(), itemStack);
                            }
                            i++;
                        }
                    }
                } finally {
                    try {
                        if (viewPushed) {
                            GlStateManager.popMatrix();
                        }
                    } finally {
                        if (lightingEnabled) {
                            RenderHelper.disableStandardItemLighting();
                        }
                        GlStateManager.disableRescaleNormal();
                        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
                        GlStateManager.disableTexture2D();
                        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
                        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                    }
                }
            }
        }
    }

    public static void renderExtraPlayerEntity(EntityPlayerSP player, double posX, double posY, float scale, float yawOffset, int z, float partialTicks) {
        RenderUtil.setRenderingInPaperDoll(true);
        GlStateManager.enableColorMaterial();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        GlStateManager.pushMatrix();
        GlStateManager.translate(posX + scale * 0.5, posY + scale * 2, 0);
        GlStateManager.scale(1, 1, -1);
        GlStateManager.translate(0, 0, -z);
        GlStateManager.scale(scale, scale, scale);
        GlStateManager.rotate(180.1F, 0, 0, 1);
        RenderHelper.enableStandardItemLighting();
        float yRot = Interpolations.lerp(player.prevRenderYawOffset, player.renderYawOffset, partialTicks) + yawOffset - 180;
        GlStateManager.rotate(yRot, 0, 1, 0);
        RenderManager renderDispatcher = Minecraft.getMinecraft().getRenderManager();
        renderDispatcher.setPlayerViewY(yRot);
        renderDispatcher.setRenderShadow(false);
        renderDispatcher.renderEntity(player, 0, 0, 0.0D, 0.0F, partialTicks, false);
        renderDispatcher.setRenderShadow(true);
        GlStateManager.popMatrix();

        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        RenderUtil.setRenderingInPaperDoll(false);
    }
}
