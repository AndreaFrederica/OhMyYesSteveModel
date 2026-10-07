package com.elfmcys.ysm.geckolib3.core.molang.context;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.ProjectileAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.molang.storage.*;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.model.provider.data.EntityModelData;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;
import com.elfmcys.ysm.molang.runtime.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.ITextComponent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;

public class MolangContext<TEntity> implements IContext<TEntity> {
    protected final TEntity entity;
    protected final AnimatableEntity<?> animatableEntity;
    protected final AnimationEvent<?> animationEvent;
    protected final EntityModelData data;
    protected final SoundInstanceManager globalSoundManager;
    protected final Random random;
    protected final MolangMemory memory;
    private final DebugSource debugSource;

    protected AnimationContext animationContext;
    protected ControllerContext controllerContext;
    protected IForeignVariableStorage foreignStorage;
    private boolean allowEmitting;

    public MolangContext(TEntity entity, AnimationEvent<?> animationEvent,
                         MolangMemory memory, Random random, SoundInstanceManager globalSoundManager) {
        this.entity = entity;
        this.animatableEntity = animationEvent.getAnimatableEntity();
        this.animationEvent = animationEvent;
        this.data = animationEvent.getExtraData();
        this.debugSource = animationEvent.getDebugSource();
        this.memory = memory;
        this.foreignStorage = memory;
        this.random = random;
        this.globalSoundManager = globalSoundManager;
    }

    // TODO
    private MolangContext(TEntity entity, MolangContext<?> context) {
        this.entity = entity;
        this.animatableEntity = context.animatableEntity;
        this.animationEvent = context.animationEvent;
        this.data = context.data;
        this.animationContext = context.animationContext;
        this.random = context.random;
        this.memory = context.memory;
        this.debugSource = context.debugSource;
        this.globalSoundManager = context.globalSoundManager;
        if (entity instanceof EntityPlayer player) {
            var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
            if (cap != null) foreignStorage = cap.getPublicVariableStorage();
        } else if (entity instanceof Entity e) {
            if (e instanceof IProjectile) {
                var cap = e.getCapability(ProjectileAnimatableCapabilityProvider.CAP, null);
                if (cap != null) foreignStorage = cap.getPublicVariableStorage();
            } else {
                var cap = e.getCapability(VehicleAnimatableCapabilityProvider.CAP, null);
                if (cap != null) foreignStorage = cap.getPublicVariableStorage();
            }
        }
    }

    @Override
    public AnimationEvent<?> animationEvent() {
        return animationEvent;
    }

    @Override
    public AnimatableEntity<?> animatableEntity() {
        return animatableEntity;
    }

    @Override
    public EntityModelData data() {
        return data;
    }

    @Override
    public AnimationContext animationContext() {
        return animationContext;
    }

    @Override
    public ControllerContext controllerContext() {
        return controllerContext;
    }

    @Override
    public Random random() {
        return random;
    }

    @Override
    public TEntity entity() {
        return entity;
    }

    @Override
    public Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    @Override
    public WorldClient level() {
        Minecraft mc = mc();
        if (mc != null) {
            return mc.world;
        } else {
            return null;
        }
    }

    // FIXME: 需要同时更新 animatable 和 entity 两个属性，再加上源属性
    @Override
    public <TChild> IContext<TChild> createChild(TChild child) {
        return new MolangContext<>(child, this);
    }

    @Override
    public ITempVariableStorage tempStorage() {
        return memory.getStackMemory();
    }

    @Override
    public IScopedVariableStorage scopedStorage() {
        return memory;
    }

    @Override
    public IForeignVariableStorage foreignStorage() {
        return foreignStorage;
    }

    @Override
    public @Nullable IContextVariableStorage contextStorage() {
        return animationContext;
    }

    @Override
    public @Nullable IValue getUserFunction(String name) {
        return animatableEntity.getUserFunction(name);
    }

    @Override
    public Object callUserFunction(ExecutionContext<?> ctx, IValue value, List<?> args) {
        if (this.memory.getStackMemory().push(args)) {
            try {
                return value.eval((ExpressionEvaluator<?>) ctx);
            } finally {
                this.memory.getStackMemory().pop();
            }
        }
        return null;
    }

    @Override
    public Object callUserFunction(ExecutionContext<?> ctx, IValue value, Function.ArgumentCollection args) {
        if (this.memory.getStackMemory().push(ctx, args)) {
            try {
                return value.eval((ExpressionEvaluator<?>) ctx);
            } finally {
                this.memory.getStackMemory().pop();
            }
        }
        return null;
    }

    @Override
    public List<?> userFunctionArgs() {
        return memory.getStackMemory().argsAccessor();
    }

    @Override
    public boolean isDebugEnabled() {
        return debugSource != null;
    }

    @Override
    public boolean allowEmitting() {
        return allowEmitting;
    }

    public void setAllowEmitting(boolean value) {
        allowEmitting = value;
    }

    @Override
    public void debugPrint(String message, Object... args) {
        if (isDebugEnabled()) {
            debugSource.print(message, args);
        }
    }

    @Override
    public void debugPrint(ITextComponent message) {
        if (isDebugEnabled()) {
            debugSource.print(message);
        }
    }

    @Override
    @Nullable
    public SoundInstanceManager getSoundManager(boolean global) {
        if (!global) {
            if (animationContext != null) {
                var manager = animationContext.soundManager();
                if (manager != null) {
                    return manager;
                }
            }
            if (controllerContext != null) {
                var manager = controllerContext.soundManager();
                if (manager != null) {
                    return manager;
                }
            }
        }
        return this.globalSoundManager;
    }

    public void setAnimationContext(AnimationContext ctx) {
        this.animationContext = ctx;
    }

    public void setControllerContext(ControllerContext ctx) {
        this.controllerContext = ctx;
    }
}
