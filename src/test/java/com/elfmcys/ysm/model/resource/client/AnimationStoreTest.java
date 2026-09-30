package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationStoreTest {
    @Test
    void cancelledAnimationRemainsEligibleForTheNextRequest() {
        var attempts = new AtomicInteger();
        try (var store = AnimationStore.lazy(List.of("walk"), name -> {
            if (attempts.getAndIncrement() == 0) {
                throw new java.util.concurrent.CancellationException("request retired");
            }
            return com.elfmcys.ysm.proto.mixel.asset.model.data.Animation.newBuilder().setName(name).build();
        }, source -> animation(source.name()))) {
            assertNull(store.get("walk"));
            assertEquals(AnimationStore.State.UNLOADED, store.state("walk"));
            assertFalse(store.hasFailures());
            assertEquals("walk", store.get("walk").name);
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void loadsAndBindsOnlyTheFirstRequestedAnimation() {
        var loads = new HashMap<String, Integer>();
        var binds = new HashMap<String, Integer>();
        var store = AnimationStore.lazy(List.of("walk", "idle"), name -> {
            loads.merge(name, 1, Integer::sum);
            return com.elfmcys.ysm.proto.mixel.asset.model.data.Animation
                    .newBuilder()
                    .setName(name)
                    .setLength(0)
                    .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.LOOP_TYPE_PLAY_ONCE)
                    .build();
        }, source -> {
            binds.merge(source.name(), 1, Integer::sum);
            return animation(source.name());
        });

        assertEquals(AnimationStore.State.UNLOADED, store.state("walk"));
        assertEquals(AnimationStore.State.UNLOADED, store.state("idle"));
        assertFalse(store.hasFailures());

        var walk = store.get("walk");

        assertEquals("walk", walk.name);
        assertEquals(1, loads.get("walk"));
        assertEquals(1, binds.get("walk"));
        assertNull(loads.get("idle"));
        assertNull(binds.get("idle"));
        assertEquals(AnimationStore.State.READY, store.state("walk"));
        assertEquals(AnimationStore.State.UNLOADED, store.state("idle"));
        assertSame(walk, store.get("walk"));
        assertEquals(1, loads.get("walk"));
    }

    @Test
    void bindFailureIsIsolatedToOneAnimation() {
        var loads = new HashMap<String, Integer>();
        var ready = animation("ready");
        var store = AnimationStore.lazy(List.of("ready", "broken"), name -> {
            loads.merge(name, 1, Integer::sum);
            return com.elfmcys.ysm.proto.mixel.asset.model.data.Animation
                    .newBuilder()
                    .setName(name)
                    .setLength(0)
                    .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.LOOP_TYPE_PLAY_ONCE)
                    .build();
        }, source -> {
            if (source.name().equals("broken")) {
                throw new IOException("bind failed");
            }
            return ready;
        });

        assertSame(ready, store.get("ready"));
        assertNull(store.get("broken"));
        assertEquals(AnimationStore.State.READY, store.state("ready"));
        assertEquals(AnimationStore.State.FAILED, store.state("broken"));
        assertTrue(store.hasFailures());
        assertSame(ready, store.get("ready"));
        assertNull(store.get("broken"));
        assertEquals(1, loads.get("ready"));
        assertEquals(1, loads.get("broken"));
    }

    @Test
    void fallsBackWhenLocalNameIsAbsentOrPayloadIsMissing() {
        var fallbackAnimation = animation("shared");
        var fallbackOnly = animation("fallback-only");
        var fallback = AnimationStore.eager(Map.of(
                "shared", fallbackAnimation,
                "fallback-only", fallbackOnly));
        var store = AnimationStore.lazy(List.of("shared"),
                name -> null, ignored -> animation("unused"), fallback);

        assertSame(fallbackAnimation, store.get("shared"));
        assertSame(fallbackOnly, store.get("fallback-only"));
    }

    @Test
    void bindsPresentLocalAnimationBeforeUsingFallback() {
        var fallbackAnimation = animation("shared");
        var localAnimation = animation("shared");
        var fallback = AnimationStore.eager(Map.of("shared", fallbackAnimation));
        var store = AnimationStore.lazy(List.of("shared"),
                name -> com.elfmcys.ysm.proto.mixel.asset.model.data.Animation
                        .newBuilder()
                        .setName(name)
                        .setLength(0)
                        .setLoop(com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType.LOOP_TYPE_PLAY_ONCE)
                        .build(),
                ignored -> localAnimation, fallback);

        assertSame(localAnimation, store.get("shared"));
    }

    @Test
    void failedAnimationFallsBackAndDoesNotRetry() {
        var fallbackAnimation = animation("shared");
        var fallback = AnimationStore.eager(Map.of("shared", fallbackAnimation));
        var loads = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var gate = new ModelResourceFailureGate() {
            @Override
            public Optional<Throwable> failure() {
                return Optional.ofNullable(failure.get());
            }

            @Override
            public void fail(Throwable cause) {
                failure.compareAndSet(null, cause);
            }
        };
        var store = AnimationStore.lazy(List.of("shared"), name -> {
            loads.incrementAndGet();
            throw new IOException("broken");
        }, ignored -> animation("unused"), fallback, ignored -> gate);

        assertSame(fallbackAnimation, store.get("shared"));
        assertSame(fallbackAnimation, store.get("shared"));
        assertEquals(1, loads.get());
        assertEquals(AnimationStore.State.FAILED, store.state("shared"));

        var reconstructed = AnimationStore.lazy(List.of("shared"), name -> {
            loads.incrementAndGet();
            return null;
        }, ignored -> animation("unused"), fallback, ignored -> gate);
        assertSame(fallbackAnimation, reconstructed.get("shared"));
        assertEquals(1, loads.get());
    }

    private static Animation animation(String name) {
        return new Animation(name, 0, LoopType.PLAY_ONCE, null,
                List.of(), List.of(), List.of());
    }
}
