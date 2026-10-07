package com.elfmcys.ysm.geckolib3.core.molang.builtin;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.capability.PlayerStateTracker;
import com.elfmcys.ysm.client.animation.molang.functions.Rot2Camera;
import com.elfmcys.ysm.client.compat.aquaacrobatics.AACompat;
import com.elfmcys.ysm.geckolib3.core.molang.binding.ContextBinding;
import com.elfmcys.ysm.geckolib3.core.molang.builtin.query.*;
import com.elfmcys.ysm.geckolib3.core.molang.context.AnimationContext;
import com.elfmcys.ysm.geckolib3.core.molang.context.ControllerContext;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.util.MathUtil;
import com.elfmcys.ysm.geckolib3.util.MolangUtils;
import com.elfmcys.ysm.mclib.utils.Interpolations;
import com.elfmcys.ysm.util.EntityUtil;
import com.elfmcys.ysm.util.EquipmentUtil;
import com.elfmcys.ysm.util.PersonView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

public class QueryBinding extends ContextBinding {
    public static final QueryBinding INSTANCE = new QueryBinding();

    private QueryBinding() {
        function("debug_output", new DebugOutput());

        function("biome_has_all_tags", new BiomeHasAllTags());
        function("biome_has_any_tag", new BiomeHasAnyTag());
        function("relative_block_has_all_tags", new RelativeBlockHasAllTags());
        function("relative_block_has_any_tag", new RelativeBlockHasAnyTag());
        function("is_item_name_any", new ItemNameAny());
        function("equipped_item_all_tags", new EquippedItemAllTags());
        function("equipped_item_any_tag", new EquippedItemAnyTags());
        function("position", new Position());
        function("position_delta", new PositionDelta());
        function("rotation_to_camera", new Rot2Camera());

        function("max_durability", new ItemMaxDurability());
        function("remaining_durability", new ItemRemainingDurability());

        var("actor_count", ctx -> ctx.level().getLoadedEntityList().size());
        var("anim_time", ctx -> getAnimationContext(ctx).map(AnimationContext::animTime).orElse(0f));
        // 目前控制器只能同时播放单一动画，所以两个 molang 都是一样的结果
        var("all_animations_finished", ctx -> getControllerContext(ctx).map(ControllerContext::isAllAnimationsFinished).orElse(false));
        var("any_animation_finished", ctx -> getControllerContext(ctx).map(ControllerContext::isAnyAnimationFinished).orElse(false));
        var("life_time", ctx -> ctx.animatableEntity().getSeekTime() / 20.0);
        var("head_x_rotation", ctx -> ctx.data().netHeadYaw);
        var("head_y_rotation", ctx -> ctx.data().headPitch);
        var("moon_phase", ctx -> ctx.level().getMoonPhase());
        var("time_of_day", ctx -> MolangUtils.normalizeTime(ctx.level().getWorldTime()));
        var("time_stamp", ctx -> ctx.level().getWorldTime());
        var("delta_time", ctx -> ctx.animatableEntity().getStateTracker().getRenderTickDelta() / 20);

        entityVar("yaw_speed", QueryBinding::getYawSpeed);
        entityVar("cardinal_facing_2d", ctx -> ctx.entity().getHorizontalFacing().getIndex());
        entityVar("distance_from_camera", ctx -> getCameraPosition(ctx.mc()).distanceTo(ctx.entity().getPositionVector()));
        entityVar("eye_target_x_rotation", ctx -> EntityUtil.getViewXRot(ctx.entity(), ctx.animationEvent().getRequestedPartialTick()));
        entityVar("eye_target_y_rotation", ctx -> EntityUtil.getViewYRot(ctx.entity(), ctx.animationEvent().getRequestedPartialTick()));
        entityVar("ground_speed", ctx -> getGroundSpeed(ctx.entity()));
        entityVar("modified_distance_moved", ctx -> ctx.entity().distanceWalkedModified);
        entityVar("vertical_speed", QueryBinding::getVerticalSpeed);
        entityVar("walk_distance", ctx -> ctx.entity().distanceWalkedOnStepModified);
        entityVar("has_rider", ctx -> ctx.entity().isBeingRidden());
        entityVar("is_first_person", ctx -> PersonView.getPersonView(ctx) == PersonView.FIRST_PERSON);
        entityVar("is_in_water", ctx -> ctx.entity().isInWater());
        entityVar("is_in_water_or_rain", ctx -> ctx.entity().isWet());
        entityVar("is_on_fire", ctx -> ctx.entity().isBurning());
        entityVar("is_on_ground", ctx -> ctx.entity().onGround);
        entityVar("is_riding", ctx -> ctx.entity().isRiding());
        entityVar("is_sneaking", ctx -> ctx.entity().onGround && AACompat.isCrouchingPose(ctx.entity()));
        entityVar("is_spectator", ctx -> ctx.entity() instanceof EntityPlayer player && player.isSpectator());
        entityVar("is_sprinting", ctx -> ctx.entity().isSprinting());
        entityVar("is_swimming", ctx -> AACompat.isSwimming(ctx.entity()));

        livingEntityVar("body_x_rotation", ctx -> Interpolations.lerp(ctx.entity().prevRotationPitch, ctx.entity().rotationPitch, ctx.animationEvent().getRequestedPartialTick()));
        livingEntityVar("body_y_rotation", ctx -> MathHelper.wrapDegrees(Interpolations.lerp(ctx.entity().prevRenderYawOffset, ctx.entity().renderYawOffset, ctx.animationEvent().getRequestedPartialTick())));
        livingEntityVar("health", QueryBinding::getHealth);
        livingEntityVar("max_health", QueryBinding::getMaxHealth);
        livingEntityVar("hurt_time", ctx -> ctx.entity().hurtTime);
        livingEntityVar("is_eating", ctx -> ctx.entity().getActiveItemStack().getItemUseAction() == EnumAction.EAT);
        livingEntityVar("is_playing_dead", ctx -> !ctx.entity().isEntityAlive());
        livingEntityVar("is_sleeping", ctx -> ctx.entity().isPlayerSleeping());
        livingEntityVar("is_using_item", ctx -> ctx.entity().isHandActive());
        livingEntityVar("item_in_use_duration", ctx -> ctx.entity().getItemInUseMaxCount() / 20.0);
        livingEntityVar("item_max_use_duration", ctx -> getMaxUseDuration(ctx.entity()) / 20.0);
        livingEntityVar("item_remaining_use_duration", ctx -> ctx.entity().getItemInUseCount() / 20.0);
        livingEntityVar("equipment_count", ctx -> getEquipmentCount(ctx.entity()));

        playerVar("cape_flap_amount", QueryBinding::getCapeFlapAmount);
        playerVar("player_level", QueryBinding::getExpLevel);
        playerVar("is_jumping", ctx -> !isFlying(ctx) && !ctx.entity().isRiding() && !ctx.entity().onGround && !ctx.entity().isInWater());

        clientPlayerVar("has_cape", ctx -> hasCape(ctx.entity()));
    }

    private static Optional<AnimationContext> getAnimationContext(IContext<?> ctx) {
        return Optional.ofNullable(ctx.animationContext());
    }

    private static Optional<ControllerContext> getControllerContext(IContext<?> ctx) {
        return Optional.ofNullable(ctx.controllerContext());
    }

    private static boolean isFlying(IContext<EntityPlayer> ctx) {
        if (ctx.animatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
            return cap.getStateTracker().isFlying();
        } else {
            return ctx.entity().capabilities.isFlying;
        }
    }

    private static int getExpLevel(IContext<EntityPlayer> ctx) {
        if (ctx.animatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
            return cap.getStateTracker().expLevel();
        } else {
            return ctx.entity().experienceLevel;
        }
    }
    private static Object getHealth(IContext<EntityLivingBase> ctx) {
        if (ctx.animatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
            return cap.getStateTracker().health();
        } else {
            return ctx.entity().getHealth();
        }
    }

    private static Object getMaxHealth(IContext<EntityLivingBase> ctx) {
        if (ctx.animatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
            return cap.getStateTracker().maxHealth();
        } else {
            return ctx.entity().getMaxHealth();
        }
    }

    private static boolean hasCape(AbstractClientPlayer player) {
        return player.hasPlayerInfo() && !player.isInvisible() && player.isWearing(EnumPlayerModelParts.CAPE) && player.getLocationCape() != null;
    }

    private static int getEquipmentCount(EntityLivingBase entity) {
        int count = 0;
        for (var slot : EntityEquipmentSlot.values()) {
            if (slot.getSlotType() != EntityEquipmentSlot.Type.ARMOR) {
                continue;
            }
            var stack = EquipmentUtil.getEquippedItem(entity, slot);
            if (!stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static int getMaxUseDuration(EntityLivingBase player) {
        ItemStack useItem = player.getActiveItemStack();
        if (useItem.isEmpty()) {
            return 0;
        } else {
            return useItem.getMaxItemUseDuration();
        }
    }

    public static Vec3d getCameraPosition(Minecraft mc) {
        Entity entity = mc.getRenderViewEntity();
        return entity != null ? ActiveRenderInfo.projectViewFromEntity(entity, 1.0F) : Vec3d.ZERO;
    }

    private static float getYawSpeed(IContext<Entity> ctx) {
        if (ctx.entity() instanceof EntityPlayerSP) {
            return PlayerStateTracker.getLocalPlayerYawSpeed();
        } else {
            return 20 * (ctx.entity().rotationYaw - ctx.entity().prevRotationYaw);
        }
    }

    private static float getGroundSpeed(Entity player) {
        return 20 * MathHelper.sqrt((player.motionX * player.motionX) + (player.motionZ * player.motionZ));
    }

    private static float getVerticalSpeed(IContext<Entity> ctx) {
        var stateStacker = ctx.animatableEntity().getStateTracker();
        var posDelta = stateStacker.getPositionDelta();
        return 20 * (float) posDelta.y / stateStacker.getRenderTickDelta();
    }
    private static float getCapeFlapAmount(IContext<EntityPlayer> ctx) {
        float pPartialTicks = ctx.animationEvent().getPartialTick();
        var pLivingEntity = ctx.entity();

        float d0 = (float) (Interpolations.lerp(pLivingEntity.prevChasingPosX, pLivingEntity.chasingPosX, pPartialTicks) - Interpolations.lerp(pLivingEntity.prevPosX, pLivingEntity.posX, pPartialTicks));
        float d1 = (float) (Interpolations.lerp(pLivingEntity.prevChasingPosY, pLivingEntity.chasingPosY, pPartialTicks) - Interpolations.lerp(pLivingEntity.prevPosY, pLivingEntity.posY, pPartialTicks));
        float d2 = (float) (Interpolations.lerp(pLivingEntity.prevChasingPosZ, pLivingEntity.chasingPosZ, pPartialTicks) - Interpolations.lerp(pLivingEntity.prevPosZ, pLivingEntity.posZ, pPartialTicks));
        float f = pLivingEntity.prevRenderYawOffset + (pLivingEntity.renderYawOffset - pLivingEntity.prevRenderYawOffset);
        float d3 = MathHelper.sin(f * (MathUtil.PI / 180F));
        float d4 = (-MathHelper.cos(f * (MathUtil.PI / 180F)));
        float f1 = d1 * 10.0F;
        f1 = MathHelper.clamp(f1, -6.0F, 32.0F);
        float f2 = (d0 * d3 + d2 * d4) * 100.0F;
        f2 = MathHelper.clamp(f2, 0.0F, 150.0F);
        if (f2 < 0.0F) {
            f2 = 0.0F;
        }

        float f4 = Interpolations.lerp(pLivingEntity.prevCameraYaw, pLivingEntity.cameraYaw, pPartialTicks);
        f1 = f1 + MathHelper.sin(Interpolations.lerp(pLivingEntity.prevDistanceWalkedModified, pLivingEntity.distanceWalkedModified, pPartialTicks) * 6.0F) * 32.0F * f4;
        if (pLivingEntity.isSneaking()) {
            f1 += 25.0F;
        }

        return MathHelper.clamp((6.0F + f2 / 2.0F + f1) / 108, 0, 1);
    }
}
