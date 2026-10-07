package com.elfmcys.ysm.geckolib3.core.molang.context;

import com.elfmcys.ysm.client.sound.instance.SoundInstanceManager;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.molang.storage.IContextVariableStorage;
import com.elfmcys.ysm.geckolib3.core.molang.storage.IForeignVariableStorage;
import com.elfmcys.ysm.geckolib3.core.molang.storage.IScopedVariableStorage;
import com.elfmcys.ysm.geckolib3.core.molang.storage.ITempVariableStorage;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.model.provider.data.EntityModelData;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import com.elfmcys.ysm.molang.runtime.Function;
import net.minecraft.client.Minecraft;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.text.ITextComponent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;

public interface IContext<TEntity> {
    TEntity entity();

    AnimatableEntity<?> animatableEntity();

    Minecraft mc();

    WorldClient level();

    AnimationEvent<?> animationEvent();

    EntityModelData data();

    @Nullable
    AnimationContext animationContext();

    @Nullable
    ControllerContext controllerContext();

    Random random();

    <TChild> IContext<TChild> createChild(TChild child);

    ITempVariableStorage tempStorage();

    IScopedVariableStorage scopedStorage();

    @Nullable
    IContextVariableStorage contextStorage();

    IForeignVariableStorage foreignStorage();

    @Nullable
    IValue getUserFunction(String name);

    Object callUserFunction(ExecutionContext<?> context, IValue value, List<?> args);

    Object callUserFunction(ExecutionContext<?> ctx, IValue value, Function.ArgumentCollection args);

    List<?> userFunctionArgs();

    boolean isDebugEnabled();

    /**
     * 是否允许生成行为（粒子、音效、骨骼变色、骨骼发光、相机变换等）
     */
    boolean allowEmitting();

    void debugPrint(String message, Object... args);

    void debugPrint(ITextComponent message);

    SoundInstanceManager getSoundManager(boolean global);
}
