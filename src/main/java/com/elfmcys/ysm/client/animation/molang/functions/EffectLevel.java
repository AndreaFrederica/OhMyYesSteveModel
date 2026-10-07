package com.elfmcys.ysm.client.animation.molang.functions;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.function.ContextFunction;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.projectile.EntityTippedArrow;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

public class EffectLevel extends ContextFunction<Entity> {
    @Override
    public boolean validateArgumentSize(int size) {
        return size >= 1;
    }

    @Override
    protected Object eval(ExecutionContext<IContext<Entity>> context, ArgumentCollection arguments) {
        int sum = 0;
        for (var i = 0; i < arguments.size(); ++i) {
            ResourceLocation effectId = arguments.getAsResourceLocation(context, i);
            if (effectId == null) {
                continue;
            }

            Potion effect = ForgeRegistries.POTIONS.getValue(effectId);
            if (effect == null) {
                continue;
            }

            if (context.entity().animatableEntity() instanceof PlayerAnimatableCapability cap && !cap.isLocalPlayer()) {
                sum += cap.getStateTracker().getEffectLevel(effect);
            } else if (context.entity().entity() instanceof EntityLivingBase living) {
                PotionEffect instance = living.getActivePotionEffect(effect);
                if (instance != null) {
                    sum += instance.getAmplifier() + 1;
                }
            } else if (context.entity().entity() instanceof EntityTippedArrow tippedArrow) {
                for (PotionEffect instance : tippedArrow.customPotionEffects) {
                    if (instance.getPotion() == effect) {
                        sum += instance.getAmplifier() + 1;
                        break;
                    }
                }
            } else {
                return null;
            }
        }

        return sum;
    }
}
