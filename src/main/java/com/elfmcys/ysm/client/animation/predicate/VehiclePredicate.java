package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.animation.condition.ConditionManager;
import com.elfmcys.ysm.client.animation.condition.ConditionalChair;
import com.elfmcys.ysm.client.animation.condition.ConditionalVehicle;
import com.elfmcys.ysm.client.compat.carryon.CarryOnCompat;
import com.elfmcys.ysm.client.compat.swem.SwemCompat;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayer;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class VehiclePredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event, ExpressionEvaluator<?> evaluator) {
        PlayState vehicleAnimation = getVehicleAnimation(event);
        return Objects.requireNonNullElse(vehicleAnimation, PlayState.STOP);
    }

    @Nullable
    public PlayState getVehicleAnimation(AnimationEvent<CustomHumanoidEntity<?>> event) {
        EntityLivingBase entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return null;
        }
        Entity vehicle = entity.getRidingEntity();
        if (vehicle == null || !vehicle.isEntityAlive()) {
            return null;
        }

        String swemAnimation = SwemCompat.getAnimation(entity);
        if (StringUtils.isNoneBlank(swemAnimation)) {
            return playAnimation(event, swemAnimation, LoopType.LOOP);
        }

        // 优先判断 chair
        ConditionManager conditionManager = event.getAnimatableEntity().getConditionManager();
        if (TlmClientCompat.isInstalled()) {
            ConditionalChair conditionalChair = conditionManager.getChair();
            if (conditionalChair != null) {
                String name = conditionalChair.doTest(entity);
                if (StringUtils.isNoneBlank(name)) {
                    return playAnimation(event, name, LoopType.LOOP);
                }
            }
        }

        // 然后才是普通载具
        ConditionalVehicle vehicleCondition = conditionManager.getVehicle();
        if (vehicleCondition != null) {
            String name = vehicleCondition.doTest(entity);
            if (StringUtils.isNoneBlank(name)) {
                return playAnimation(event, name, LoopType.LOOP);
            }
        }

        // 兼容旧版本的几个动画
        if (vehicle instanceof EntityPig) {
            return playAnimation(event, "ride_pig", LoopType.LOOP);
        }
        if (vehicle instanceof AbstractHorse) {
            return playAnimation(event, "ride", LoopType.LOOP);
        }
        if (vehicle instanceof EntityBoat) {
            return playAnimation(event, "boat", LoopType.LOOP);
        }

        // carry on 兼容
        boolean playerIsOnPrincess = entity instanceof EntityPlayer player && CarryOnCompat.isCarryOnPrincess(player);
        boolean maidIsOnPrincess = TlmClientCompat.isMaid(entity) && entity.getRidingEntity() instanceof EntityPlayer;
        if (playerIsOnPrincess || maidIsOnPrincess) {
            return playAnimation(event, "carryon:princess", LoopType.LOOP);
        }

        // 安装女仆模组后，那么需要兼容几个女仆的内容
        PlayState playState = TlmClientCompat.getMaidVehicleAnimation(event, entity, vehicle);
        if (playState != null) {
            return playState;
        }

        return playAnimation(event, "sit", LoopType.LOOP);
    }
}
