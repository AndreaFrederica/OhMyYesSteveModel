package com.elfmcys.ysm.model.resource.client.audio;

import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioRetentionCacheTest {
    @Test
    void ttlExpiresExactlyThirtySecondsAfterTheLatestAcquire() {
        var clock = new AtomicLong();
        try (var cache = new AudioRetentionCache(64, 30, clock::get)) {
            var key = key(1);
            cache.retainAfterAcquire(key, new FakeAudio(10, false));

            clock.set(29);
            close(cache.acquire(key));
            clock.set(58);
            close(cache.acquire(key));
            clock.set(88);
            assertNull(cache.acquire(key));
            assertEquals(0, cache.retainedBytes());
        }
    }

    @Test
    void encodedAndPcmShareOneAccessOrderedBudget() {
        try (var cache = new AudioRetentionCache(64, 1_000, () -> 0)) {
            var a = key(1);
            var b = key(2);
            var c = key(3);
            cache.retainAfterAcquire(a, new FakeAudio(24, false));
            cache.retainAfterAcquire(b, new FakeAudio(24, true));
            close(cache.acquire(a));
            cache.retainAfterAcquire(c, new FakeAudio(24, false));

            assertNull(cache.acquire(b));
            close(cache.acquire(a));
            close(cache.acquire(c));
            assertEquals(48, cache.retainedBytes());
        }
    }

    @Test
    void pcmPublicationAtomicallyReplacesEncodedAndKeepsAcquireOrder() {
        try (var cache = new AudioRetentionCache(64, 30, () -> 0)) {
            var key = key(1);
            var receipt = cache.retainAfterAcquire(key, new FakeAudio(20, false));
            assertTrue(cache.publishPcm(key, new FakeAudio(12, true), receipt));
            var acquired = cache.acquire(key);
            assertTrue(acquired.audio().pcm());
            acquired.audio().close();
            assertEquals(12, cache.retainedBytes());
            assertFalse(cache.publishPcm(key, new FakeAudio(8, true), receipt));
        }
    }

    @Test
    void pcmPublicationUsesTheRetainedEntrysLatestAcquire() {
        var clock = new AtomicLong();
        try (var cache = new AudioRetentionCache(64, 30, clock::get)) {
            var key = key(1);
            var candidateReceipt = cache.retainAfterAcquire(
                    key, new FakeAudio(20, false));

            clock.set(29);
            close(cache.acquire(key));
            clock.set(31);

            assertTrue(cache.publishPcm(
                    key, new FakeAudio(12, true), candidateReceipt));
            var acquired = cache.acquire(key);
            assertTrue(acquired.audio().pcm());
            acquired.audio().close();
        }
    }

    @Test
    void oversizedAcquireIsDeliveredButNotRetained() {
        try (var cache = new AudioRetentionCache(64, 30, () -> 0)) {
            cache.retainAfterAcquire(key(1), new FakeAudio(65, false));
            assertEquals(0, cache.entryCount());
            assertEquals(0, cache.retainedBytes());
        }
    }

    private static void close(AudioRetentionCache.Acquisition acquisition) {
        if (acquisition != null) {
            acquisition.audio().close();
        }
    }

    private static AudioCacheKey key(int value) {
        var model = new byte[Hash256.SIZE];
        var container = new byte[Hash256.SIZE];
        var stream = new byte[Hash256.SIZE];
        model[0] = (byte) value;
        container[0] = (byte) (value + 1);
        stream[0] = (byte) (value + 2);
        return new AudioCacheKey(
                new ModelFileIdentity(new Hash256(model), new Hash256(container)),
                value, SupportedAudioProbe.Encoding.OGG_VORBIS,
                2, 44_100, 1_000, new Hash256(stream));
    }

    private static final class FakeAudio implements CachedAudio {
        private final int size;
        private final boolean pcm;
        private final AtomicInteger closes;
        private boolean closed;

        private FakeAudio(int size, boolean pcm) {
            this(size, pcm, new AtomicInteger());
        }

        private FakeAudio(int size, boolean pcm, AtomicInteger closes) {
            this.size = size;
            this.pcm = pcm;
            this.closes = closes;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean pcm() {
            return pcm;
        }

        @Override
        public CachedAudio acquire() {
            return new FakeAudio(size, pcm, closes);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                closes.incrementAndGet();
            }
        }
    }
}
