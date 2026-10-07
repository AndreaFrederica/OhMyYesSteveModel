package com.elfmcys.ysm.client.animation.molang;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.animation.Priority;
import com.elfmcys.ysm.client.animation.molang.functions.*;
import com.elfmcys.ysm.client.compat.aquaacrobatics.AACompat;
import com.elfmcys.ysm.client.compat.backpack.sophisticated.SophisticatedCompat;
import com.elfmcys.ysm.client.compat.backport.BackportCompat;
import com.elfmcys.ysm.client.compat.bettercombat.BetterCombatCompat;
import com.elfmcys.ysm.client.compat.carryon.CarryOnCompat;
import com.elfmcys.ysm.client.compat.create.CreateCompat;
import com.elfmcys.ysm.client.compat.immersivemelodies.ImmersiveMelodiesCompat;
import com.elfmcys.ysm.client.compat.ironsspellbooks.IronsSpellBooksCompat;
import com.elfmcys.ysm.client.compat.parcool.ParCoolCompat;
import com.elfmcys.ysm.client.compat.slashblade.SlashBladeCompat;
import com.elfmcys.ysm.client.compat.swem.SwemCompat;
import com.elfmcys.ysm.client.compat.tacz.TACZCompat;
import com.elfmcys.ysm.client.controller.collections.PlayerControllerCollection;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.AnimationState;
import com.elfmcys.ysm.geckolib3.core.molang.binding.ContextBinding;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.mclib.utils.Interpolations;
import com.elfmcys.ysm.util.LazyValue;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

import java.util.function.Predicate;

public class CtrlBinding extends ContextBinding {
    public static final LazyValue<CtrlBinding> INSTANCE = new LazyValue<>(CtrlBinding::new);

    public static final int STATE_CONTINUE = 2;
    public static final int STATE_STOP = 3;
    public static final int STATE_PAUSE = 4;
    public static final int STATE_BYPASS = 5;

    public static final int LOOP = 10;
    public static final int PLAY_ONCE = 11;
    public static final int HOLD_ON_LAST_FRAME = 12;

    private static ReferenceArrayList<Condition>[] DATA;
    private static final float MIN_SPEED = 0.05f;

    private CtrlBinding() {
        // 主动画的
        register("death", Priority.HIGHEST, entity -> !entity.isEntityAlive());
        register("riptide", Priority.HIGHEST, BackportCompat::isAutoSpinAttack);
        register("sleep", Priority.HIGHEST, EntityLivingBase::isPlayerSleeping);
        register("swim", Priority.HIGHEST, AACompat::isSwimming);
        register("climb", Priority.HIGHEST, entity -> AACompat.isSwimmingPose(entity) && isMoving(entity));
        register("climbing", Priority.HIGHEST, AACompat::isSwimmingPose);

        register("ladder_up", Priority.HIGHEST, entity -> entity.isOnLadder() && getVerticalSpeed(entity) > 0);
        register("ladder_stillness", Priority.HIGHEST, entity -> entity.isOnLadder() && getVerticalSpeed(entity) == 0);
        register("ladder_down", Priority.HIGHEST, entity -> entity.isOnLadder() && getVerticalSpeed(entity) < 0);

        register("fly", Priority.HIGH, CtrlBinding::isFlying);
        register("elytra_fly", Priority.HIGH, entity -> AACompat.isFallFlyingPose(entity) && entity.isElytraFlying());

        register("swim_stand", Priority.NORMAL, entity -> entity.isInWater() && !entity.onGround);
        register("attacked", Priority.NORMAL, entity -> entity.hurtTime > 0);
        register("jump", Priority.NORMAL, entity -> !entity.onGround && !entity.isInWater());
        register("sneak", Priority.NORMAL, entity -> entity.onGround && AACompat.isCrouchingPose(entity) && isMoving(entity));
        register("sneaking", Priority.NORMAL, entity -> entity.onGround && AACompat.isCrouchingPose(entity));

        register("run", Priority.LOW, entity -> entity.onGround && entity.isSprinting());
        register("walk", Priority.LOW, entity -> entity.onGround && isMoving(entity));

        register("idle", Priority.LOWEST, entity -> true);

        // 轮盘动画预测
        var("playing_extra_animation", CtrlBinding::checkExtraAnimationState);

        // 条件动画的
        function("hold", HandItemCheck.holdCheck());
        function("swing", HandItemCheck.swingCheck());
        function("use", HandItemCheck.useCheck());
        function("armor", ArmorCheck.armorCheck());
        function("ride", RideCheck.rideCheck());

        // 模组的
        CarryOnCompat.addBinding(this);
        TACZCompat.addBinding(this);
        SwemCompat.addBinding(this);
        ParCoolCompat.addBinding(this);
        SlashBladeCompat.addBinding(this);
        SophisticatedCompat.addBinding(this);
        CreateCompat.addBinding(this);
        BetterCombatCompat.addBinding(this);
        ImmersiveMelodiesCompat.addBinding(this);
        IronsSpellBooksCompat.addBinding(this);

        // 硬编码预测函数用
        constValue("state_continue", STATE_CONTINUE);
        constValue("state_stop", STATE_STOP);
        constValue("state_pause", STATE_PAUSE);
        constValue("state_bypass", STATE_BYPASS);

        constValue("loop", LOOP);
        constValue("play_once", PLAY_ONCE);
        constValue("hold_on_last_frame", HOLD_ON_LAST_FRAME);

        function("set_animation", new SetAnimation());
        function("set_beginning_transition_length", new SetBeginningTransitionLength());
        function("reset", new ResetController());
        function("indicate_reload", new IndicateReload());
    }

    private static boolean checkExtraAnimationState(IContext<Object> ctx) {
        if (ctx.animatableEntity() instanceof CustomPlayerEntity entity && entity.isPlayingExtraAnimation()) {
            return entity.getCodedAnimationStates(PlayerControllerCollection.CAP_CONTROLLER) != AnimationState.IDLE;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void register(String name, int priority, Predicate<IContext<EntityLivingBase>> predicate) {
        if (DATA == null) {
            DATA = new ReferenceArrayList[Priority.LOWEST + 1];
            for (int i = 0; i < DATA.length; i++) {
                DATA[i] = new ReferenceArrayList<>(6);
            }
        }
        Condition condition = new Condition(name, priority, predicate);
        DATA[priority].add(condition);
        livingEntityVar(name, ctx -> testCondition(name, ctx));
    }

    private void register(String name, int priority, LivingEntityPredicate predicate) {
        register(name, priority, (Predicate<IContext<EntityLivingBase>>) predicate);
    }

    private static boolean testCondition(String name, IContext<EntityLivingBase> context) {
        EntityLivingBase entity = context.entity();

        var stateTracker = context.animatableEntity().getStateTracker();
        if (stateTracker.getMainAnimationCache() != null) {
            return name.equals(stateTracker.getMainAnimationCache());
        }

        if (context.animatableEntity() instanceof IPreviewEntity) {
            stateTracker.setMainAnimationCache("");
            return false;
        }

        /*
        // 跑酷
        if (entity instanceof EntityPlayer player) {
            boolean parcool = ParCoolCompat.hasAnimation(player);
            if (parcool) {
                stateTracker.setMainAnimationCache("");
                return false;
            }
        }
        */

        // 载具
        Entity vehicle = entity.getRidingEntity();
        if (vehicle != null && vehicle.isEntityAlive()) {
            stateTracker.setMainAnimationCache("");
            return false;
        }

        for (int i = Priority.HIGHEST; i <= Priority.LOWEST; i++) {
            for (Condition condition : DATA[i]) {
                if (condition.predicate().test(context)) {
                    stateTracker.setMainAnimationCache(condition.name());
                    return condition.name().equals(name);
                }
            }
        }
        stateTracker.setMainAnimationCache("");
        return false;
    }

    private static boolean isMoving(EntityLivingBase entity) {
        float partialTick = Minecraft.getMinecraft().getRenderPartialTicks();
        float limbSwingAmount = Interpolations.lerp(entity.prevLimbSwingAmount, entity.limbSwingAmount, partialTick);
        return Math.abs(limbSwingAmount) > MIN_SPEED;
    }

    private static float getVerticalSpeed(EntityLivingBase entity) {
        return 20 * (float) (entity.posY - entity.prevPosY);
    }

    private static boolean isFlying(IContext<EntityLivingBase> ctx) {
        if (ctx.animatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
            return cap.getStateTracker().isFlying();
        } else if (ctx.entity() instanceof EntityPlayer player) {
            return player.capabilities.isFlying;
        }
        return false;
    }

    private record Condition(String name, int priority, Predicate<IContext<EntityLivingBase>> predicate) {
    }

    private interface LivingEntityPredicate extends Predicate<IContext<EntityLivingBase>> {
        boolean testLivingEntity(EntityLivingBase entity);

        default boolean test(IContext<EntityLivingBase> context) {
            return testLivingEntity(context.entity());
        }
    }
}
