package com.elfmcys.ysm.client.controller;

/* 1.12.2 无沉浸飞行器（immersive-aircraft）与简易飞机（simple-planes），暂缓兼容
import com.elfmcys.ysm.client.compat.ImmersiveAircraftCompat;
import com.elfmcys.ysm.client.compat.SimplePlaneCompat;
*/
import com.elfmcys.ysm.client.entity.CustomVehicleEntity;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.IBoneAnimationQueue;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.keyframe.AnimationVec3;
import com.elfmcys.ysm.geckolib3.core.molang.context.MolangContext;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class VehicleOriginController implements IAnimationController<CustomVehicleEntity> {
    private final String name;
    private final CustomVehicleEntity entity;
    private final AnimationQueue animationQueue;

    private BoneTopLevelSnapshot rootBone;
    private AnimationVec3 rotation;

    public VehicleOriginController(CustomVehicleEntity entity, String name) {
        this.name = name;
        this.entity = entity;
        this.animationQueue = new AnimationQueue();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getStateName() {
        return "[Coded]";
    }

    @Nullable
    public Vector3f getRotation() {
        return rotation;
    }

    @Override
    public void updateModel(List<BoneTopLevelSnapshot> modelRendererList, Object2ReferenceMap<String, List<IValue>> eventHandlers) {
        rootBone = modelRendererList.isEmpty() ? null : modelRendererList.get(0);
    }

    @Override
    public void process(AnimationEvent<CustomVehicleEntity> event, ExpressionEvaluator<MolangContext<?>> evaluator, boolean allowEmitting) {
        /*
        ImmersiveAircraftCompat.getRotation(event).or(() -> SimplePlaneCompat.getRotation(event)).ifPresent(q -> {
            rotation = new AnimationVec3(q);
            rotation.setEndingTransitionPercentProgressIfLess(0);
        });
        */
    }

    @Override
    public void visitBoneAnimationQueues(Consumer<IBoneAnimationQueue> visitor) {
        if (rootBone != null && rotation != null) {
            visitor.accept(animationQueue);
        }
    }

    @Override
    public void clear() {
        rootBone = null;
        rotation = null;
    }

    private class AnimationQueue implements IBoneAnimationQueue {
        @Override
        public BoneTopLevelSnapshot getSnapshot() {
            return rootBone;
        }

        @Override
        public Optional<AnimationVec3> pollRotationPoint(ExpressionEvaluator<MolangContext<?>> evaluator) {
            return Optional.ofNullable(rotation);
        }

        @Override
        public Optional<AnimationVec3> pollPositionPoint(ExpressionEvaluator<MolangContext<?>> evaluator) {
            return Optional.empty();
        }

        @Override
        public Optional<AnimationVec3> pollScalePoint(ExpressionEvaluator<MolangContext<?>> evaluator) {
            return Optional.empty();
        }
    }
}
