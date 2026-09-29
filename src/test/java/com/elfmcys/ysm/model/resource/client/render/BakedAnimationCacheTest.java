package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.proto.mixel.asset.model.data.Animation;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFile;
import com.elfmcys.ysm.proto.mixel.asset.model.data.LoopType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakedAnimationCacheTest {
    @Test
    void allowsSameAnimationNameInDifferentAnimationSets() {
        var main = animationFile("main");
        var firstPerson = animationFile("fp_arm");

        assertEquals("parallel0", assertDoesNotThrow(() -> BakedAnimationCache.flatten(List.of(main)))
                .get(0).name());
        assertEquals("parallel0", assertDoesNotThrow(() -> BakedAnimationCache.flatten(List.of(firstPerson)))
                .get(0).name());
    }

    @Test
    void loadsDuplicateAnimationNameWithinOneAnimationSet(@TempDir Path temp) {
        var cacheRoot = temp.resolve("cache");
        var cache = new BakedAnimationCache(cacheRoot, new AtomicSharedCache(cacheRoot));

        try (var store = assertDoesNotThrow(() -> cache.loadOrBake(
                container(1), "player", "main", hash(2),
                List.of(animationFile("main"), animationFile("extra")), null,
                ignored -> ModelResourceFailureGate.none()))) {
            assertEquals(1, store.size());
            assertTrue(store.containsKey("parallel0"));
        }
    }

    @Test
    void usesUnstableBakeCacheSuffixes() {
        assertEquals(".geo.ysm-cache", BakedModelCache.CACHE_SUFFIX);
        assertEquals(".anim.ysm-cache", BakedAnimationCache.CACHE_SUFFIX);
        assertEquals("ysmlib-java-bake-1", BakedModelCache.CACHE_ABI);
        assertEquals("animation-0.3.0-unstable", BakedAnimationCache.CACHE_ABI);
    }

    @Test
    void preservesSourceOrderAndIncludesTargetAndSetInBakeInputHash(@TempDir Path temp)
            throws Exception {
        var first = animationFile("first", "walk", "idle");
        var second = animationFile("second", "jump");

        assertEquals(List.of("walk", "idle", "jump"),
                BakedAnimationCache.flatten(List.of(first, second)).stream()
                        .map(Animation::name).toList());

        var definitionHash = hash(1);
        var containerId = container(2);
        var main = BakedAnimationCache.bakeInputHash(
                containerId, definitionHash, "player", "main");
        assertNotEquals(main,
                BakedAnimationCache.bakeInputHash(
                        container(3), definitionHash, "player", "main"));
        assertNotEquals(main,
                BakedAnimationCache.bakeInputHash(
                        containerId, definitionHash, "vehicle", "main"));
        assertNotEquals(main,
                BakedAnimationCache.bakeInputHash(
                        containerId, definitionHash, "player", "fp_arm"));

        var cacheRoot = temp.resolve("cache");
        var cache = new BakedAnimationCache(cacheRoot, new AtomicSharedCache(cacheRoot));
        assertEquals(cacheRoot.resolve(BakedAnimationCache.CACHE_ABI)
                        .resolve(containerId.toString())
                        .resolve(main + BakedAnimationCache.CACHE_SUFFIX),
                cache.path(containerId, main));

        var emptyHash = BakedAnimationCache.bakeInputHash(
                containerId, definitionHash, "player", "empty");
        var empty = cache.loadOrBake(containerId, "player", "empty",
                definitionHash, List.of(), null,
                ignored -> ModelResourceFailureGate.none());
        assertTrue(empty.keySet().isEmpty());
        assertTrue(Files.isRegularFile(
                cache.path(containerId, emptyHash)));
    }

    @Test
    void geometryBakeHashAndPathIncludeContainerResourceTextureAndOptions(
            @TempDir Path temp) {
        var containerId = container(1);
        var textureHash = hash(2).bytes();
        var base = BakedModelCache.bakeHash(
                containerId, "player/main", textureHash);

        assertEquals(base, BakedModelCache.bakeHash(
                containerId, "player/main", textureHash));
        assertNotEquals(base, BakedModelCache.bakeHash(
                container(3), "player/main", textureHash));
        assertNotEquals(base, BakedModelCache.bakeHash(
                containerId, "player/arm", textureHash));
        assertNotEquals(base, BakedModelCache.bakeHash(
                containerId, "player/main", hash(4).bytes()));

        var cacheRoot = temp.resolve("cache");
        var cache = new BakedModelCache(cacheRoot, new AtomicSharedCache(cacheRoot));
        assertEquals(containerId.toString(),
                cache.path(containerId, base).getParent().getFileName().toString());
        assertNotEquals(cache.path(containerId, base),
                cache.path(container(3), BakedModelCache.bakeHash(
                        container(3), "player/main", textureHash)));
    }

    private static Map.Entry<String, AnimationFile> animationFile(String key) {
        return animationFile(key, "parallel0");
    }

    private static Map.Entry<String, AnimationFile> animationFile(
            String key, String... names) {
        var file = AnimationFile.newBuilder();
        for (var name : names) {
            file.addAnimations(Animation.newBuilder()
                    .setName(name)
                    .setLength(0)
                    .setLoop(LoopType.LOOP_TYPE_PLAY_ONCE)
                    .build());
        }
        return Map.entry(key, file.build());
    }

    private static Hash256 hash(int value) {
        var bytes = new byte[Hash256.SIZE];
        Arrays.fill(bytes, (byte) value);
        return new Hash256(bytes);
    }

    private static Hash256 container(int value) {
        return hash(value);
    }
}
