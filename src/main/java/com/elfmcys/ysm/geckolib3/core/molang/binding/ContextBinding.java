package com.elfmcys.ysm.geckolib3.core.molang.binding;

import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.variable.IValueEvaluator;
import com.elfmcys.ysm.geckolib3.core.molang.variable.LambdaVariable;
import com.elfmcys.ysm.geckolib3.core.molang.variable.entity.*;
import com.elfmcys.ysm.molang.parser.ast.StringExpression;
import com.elfmcys.ysm.molang.runtime.Function;
import com.elfmcys.ysm.molang.runtime.binding.ObjectBinding;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.entity.projectile.EntityThrowable;

import java.util.Set;

public class ContextBinding implements ObjectBinding {
    protected final Object2ReferenceOpenHashMap<String, Object> bindings = new Object2ReferenceOpenHashMap<>();

    @Override
    public Object getProperty(String name) {
        return bindings.get(name);
    }

    public Set<String> getAllName() {
        return bindings.keySet();
    }

    public void function(String name, Function function) {
        bindings.put(name, function);
    }

    public void constValue(String name, Object value) {
        if (value instanceof String str) {
            bindings.put(name, new StringExpression(str));
        } else if (value instanceof Number num) {
            bindings.put(name, num.floatValue());
        } else if (value instanceof Boolean b) {
            bindings.put(name, b ? 1f : 0f);
        } else {
            bindings.put(name, value);
        }
    }

    public void var(String name, IValueEvaluator<?, IContext<Object>> evaluator) {
        bindings.put(name, new LambdaVariable<>(evaluator));
    }

    public void entityVar(String name, IValueEvaluator<?, IContext<Entity>> evaluator) {
        bindings.put(name, new EntityVariable(evaluator));
    }

    public void livingEntityVar(String name, IValueEvaluator<?, IContext<EntityLivingBase>> evaluator) {
        bindings.put(name, new LivingEntityVariable(evaluator));
    }

    public void playerVar(String name, IValueEvaluator<?, IContext<EntityPlayer>> evaluator) {
        bindings.put(name, new PlayerVariable(evaluator));
    }

    public void clientPlayerVar(String name, IValueEvaluator<?, IContext<AbstractClientPlayer>> evaluator) {
        bindings.put(name, new ClientPlayerVariable(evaluator));
    }

    public void localPlayerVar(String name, IValueEvaluator<?, IContext<EntityPlayerSP>> evaluator) {
        bindings.put(name, new LocalPlayerVariable(evaluator));
    }

    public void projectileVar(String name, IValueEvaluator<?, IContext<IProjectile>> evaluator) {
        bindings.put(name, new ProjectileVariable(evaluator));
    }

    public void throwableItemProjectileVar(String name, IValueEvaluator<?, IContext<EntityThrowable>> evaluator) {
        bindings.put(name, new ThrowableItemProjectileVariable(evaluator));
    }

    public void fishingHookVar(String name, IValueEvaluator<?, IContext<EntityFishHook>> evaluator) {
        bindings.put(name, new FishingHookVariable(evaluator));
    }

    public void abstractArrowVar(String name, IValueEvaluator<?, IContext<EntityArrow>> evaluator) {
        bindings.put(name, new AbstractArrowVariable(evaluator));
    }
}
