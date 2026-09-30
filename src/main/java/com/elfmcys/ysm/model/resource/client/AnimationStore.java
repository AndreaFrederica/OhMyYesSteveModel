package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Per-render-target animation ownership with local-first default fallback. */
public final class AnimationStore extends Object2ReferenceOpenHashMap<String, Animation>
        implements AutoCloseable {
    private final AnimationSource source;
    private final AnimationBinder binder;
    private final AnimationStore fallback;
    private final Function<String, ModelResourceFailureGate> failureGates;
    private final Map<String, Slot> slots = new HashMap<>();
    private boolean closed;

    public AnimationStore() {
        this(null, null, null, ignored -> ModelResourceFailureGate.none());
    }

    private AnimationStore(AnimationSource source, AnimationBinder binder,
                           AnimationStore fallback,
                           Function<String, ModelResourceFailureGate> failureGates) {
        this.source = source;
        this.binder = binder;
        this.fallback = fallback;
        this.failureGates = failureGates;
    }

    public static AnimationStore lazy(Iterable<String> names,
                                      AnimationSource source,
                                      AnimationBinder binder) {
        return lazy(names, source, binder, null);
    }

    public static AnimationStore lazy(Iterable<String> names,
                                      AnimationSource source,
                                      AnimationBinder binder,
                                      AnimationStore fallback) {
        return lazy(names, source, binder, fallback,
                ignored -> ModelResourceFailureGate.none());
    }

    public static AnimationStore lazy(Iterable<String> names,
                                      AnimationSource source,
                                      AnimationBinder binder,
                                      AnimationStore fallback,
                                      Function<String, ModelResourceFailureGate> failureGates) {
        var result = new AnimationStore(Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(binder, "binder"), fallback,
                Objects.requireNonNull(failureGates, "failureGates"));
        for (var name : names) {
            Objects.requireNonNull(name, "animation name");
            if (result.containsLocal(name)) {
                throw new IllegalArgumentException("Duplicate animation name: " + name);
            }
            result.put(name, null);
            result.slots.put(name, new Slot(State.UNLOADED, null));
        }
        return result;
    }

    public static AnimationStore eager(Map<String, Animation> animations) {
        var result = new AnimationStore();
        animations.forEach((name, animation) -> {
            var checkedName = Objects.requireNonNull(name, "animation name");
            var checkedAnimation = Objects.requireNonNull(animation, "animation");
            result.put(checkedName, checkedAnimation);
            result.slots.put(checkedName, new Slot(State.READY, checkedAnimation));
        });
        return result;
    }

    @Override
    public synchronized Animation get(Object key) {
        requireOpen();
        if (key instanceof String name && containsLocal(name)) {
            var slot = slots.get(name);
            if (slot == null || slot.state() == State.READY || source == null) {
                return slot == null ? super.get(name) : slot.animation();
            }
            if (slot.state() == State.FAILED) {
                return fallback == null ? null : fallback.get(name);
            }
            var failureGate = Objects.requireNonNull(failureGates.apply(name), "failure gate");
            if (failureGate.failure().isPresent()) {
                slots.put(name, new Slot(State.FAILED, null));
                return fallback == null ? null : fallback.get(name);
            }
            try {
                var proto = source.load(name);
                if (proto == null) {
                    failureGate.fail(new FileNotFoundException(
                            "Baked animation payload is missing: " + name));
                    slots.put(name, new Slot(State.FAILED, null));
                    return fallback == null ? null : fallback.get(name);
                }
                var value = Objects.requireNonNull(binder.bind(proto), "bound animation");
                super.put(name, value);
                slots.put(name, new Slot(State.READY, value));
                return value;
            } catch (Exception error) {
                if (ResourceFailure.isCancellation(error)) {
                    return fallback == null ? null : fallback.get(name);
                }
                failureGate.fail(error);
                slots.put(name, new Slot(State.FAILED, null));
                return fallback == null ? null : fallback.get(name);
            }
        }
        return fallback == null ? null : fallback.get(key);
    }

    @Override
    public Animation getOrDefault(Object key, Animation defaultValue) {
        var value = get(key);
        return value == null ? defaultValue : value;
    }

    @Override
    public synchronized boolean containsKey(Object key) {
        requireOpen();
        return containsLocal(key) || fallback != null && fallback.containsKey(key);
    }

    private boolean containsLocal(Object key) {
        return super.containsKey(key);
    }

    @Override
    public synchronized ObjectSet<String> keySet() {
        requireOpen();
        if (fallback == null) {
            return super.keySet();
        }
        var names = new ObjectOpenHashSet<>(super.keySet());
        names.addAll(fallback.keySet());
        return ObjectSets.unmodifiable(names);
    }

    public synchronized State state(String name) {
        requireOpen();
        var slot = slots.get(name);
        return slot == null ? null : slot.state();
    }

    public synchronized boolean hasFailures() {
        requireOpen();
        return slots.values().stream().anyMatch(slot -> slot.state() == State.FAILED);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Animation store is closed");
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        slots.clear();
        super.clear();
    }

    @FunctionalInterface
    public interface AnimationSource {
        com.elfmcys.ysm.proto.mixel.asset.model.data.Animation load(String animationName) throws Exception;
    }

    @FunctionalInterface
    public interface AnimationBinder {
        Animation bind(com.elfmcys.ysm.proto.mixel.asset.model.data.Animation animation) throws Exception;
    }

    public enum State {
        UNLOADED,
        READY,
        FAILED
    }

    private record Slot(State state, Animation animation) {
    }
}
