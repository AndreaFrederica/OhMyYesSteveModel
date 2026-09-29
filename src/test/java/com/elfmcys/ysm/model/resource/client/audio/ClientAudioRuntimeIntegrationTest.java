package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.client.sound.stream.AudioStreamProvider;
import com.elfmcys.ysm.client.sound.stream.CustomAudioStream;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.CatalogModelLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.SoundSource;
import com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(NativeLibraryExtension.class)
class ClientAudioRuntimeIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void hotPcmStillChecksCurrentAccessAndDoesNotRereadEncoded() throws Exception {
        var content = createContent();
        var reads = new AtomicInteger();
        var counted = new CountingContent(content, reads);
        var sound = content.modelFile().getCommon().sounds().get("tone_vorbis");
        var source = new SoundSource(
                content.representation().identity(), sound);
        var authorized = new AtomicBoolean(true);
        try (var runtime = new ClientAudioRuntime(Runnable::run, requested -> {
            if (!authorized.get()) {
                throw AssetLoadException.access("denied");
            }
            return new ClientAudioRuntime.SoundAccess(counted, null);
        })) {
            var cold = runtime.createPlayback(source).openStream(false).join();
            byte[] coldBytes;
            try (cold) {
                coldBytes = readAll(cold);
            }
            assertEquals(1, reads.get());

            var hot = runtime.createPlayback(source).openStream(false).join();
            byte[] hotBytes;
            try (hot) {
                hotBytes = readAll(hot);
            }
            assertArrayEquals(coldBytes, hotBytes);
            assertEquals(1, reads.get());

            authorized.set(false);
            var rejected = runtime.createPlayback(source).openStream(false);
            var failure = assertThrows(CompletionException.class, rejected::join);
            assertInstanceOf(AssetLoadException.class, failure.getCause());
            assertEquals(1, reads.get());
        } finally {
            content.representation().close();
        }
    }

    @Test
    void stopAfterPayloadReadDoesNotRetainEncoded() throws Exception {
        var content = createContent();
        var reads = new AtomicInteger();
        var counted = new CountingContent(content, reads);
        var sound = content.modelFile().getCommon().sounds().get("tone_vorbis");
        var source = new SoundSource(content.representation().identity(), sound);
        var payloadRead = new CompletableFuture<Void>();
        var allowReadReturn = new CompletableFuture<Void>();
        var blocked = new BlockingAfterReadContent(
                counted, payloadRead, allowReadReturn);
        var firstAccess = new AtomicBoolean(true);
        var worker = Executors.newSingleThreadExecutor();
        try (var runtime = new ClientAudioRuntime(worker, requested ->
                new ClientAudioRuntime.SoundAccess(
                        firstAccess.getAndSet(false) ? blocked : counted, null))) {
            var playback = runtime.createPlayback(source);
            var stoppedResult = playback.openStream(false);
            payloadRead.get(10, TimeUnit.SECONDS);

            playback.stop();
            assertThrows(CancellationException.class, stoppedResult::join);
            allowReadReturn.complete(null);
            worker.submit(() -> {
            }).get(10, TimeUnit.SECONDS);

            var retry = runtime.createPlayback(source).openStream(false).join();
            try (retry) {
                readAll(retry);
            }
            assertEquals(2, reads.get());
        } finally {
            allowReadReturn.complete(null);
            worker.shutdownNow();
            worker.awaitTermination(10, TimeUnit.SECONDS);
            content.representation().close();
        }
    }

    @Test
    void decoderConfirmedInvalidContentIsRetiredBeforeTheNextAcquire() throws Exception {
        var content = createContent(corruptVorbisSetup(
                Files.readAllBytes(fixtureRoot().resolve("vorbis-under.ogg"))));
        var reads = new AtomicInteger();
        var counted = new CountingContent(content, reads);
        var sound = content.modelFile().getCommon().sounds().get("tone_vorbis");
        var source = new SoundSource(content.representation().identity(), sound);
        try (var runtime = new ClientAudioRuntime(Runnable::run,
                requested -> new ClientAudioRuntime.SoundAccess(counted, null))) {
            assertDecoderContentFailure(runtime.createPlayback(source).openStream(false));
            assertDecoderContentFailure(runtime.createPlayback(source).openStream(false));
            assertEquals(2, reads.get(),
                    "decoder-confirmed invalid encoded data must not remain a cache hit");
        } finally {
            content.representation().close();
        }
    }

    @Test
    void capabilityFailureAndCancellationDoNotPoisonHealthyRetention() throws Exception {
        var unsupported = createContent(withVorbisSampleRate(
                Files.readAllBytes(fixtureRoot().resolve("vorbis-under.ogg")),
                4_000_000_001L));
        var capabilityReads = new AtomicInteger();
        var capabilityContent = new CountingContent(unsupported, capabilityReads);
        var capabilitySound = unsupported.modelFile().getCommon().sounds().get("tone_vorbis");
        var capabilitySource = new SoundSource(
                unsupported.representation().identity(), capabilitySound);
        try (var runtime = new ClientAudioRuntime(Runnable::run,
                requested -> new ClientAudioRuntime.SoundAccess(capabilityContent, null))) {
            assertCapabilityFailure(runtime.createPlayback(capabilitySource).openStream(false));
            assertCapabilityFailure(runtime.createPlayback(capabilitySource).openStream(false));
            assertEquals(1, capabilityReads.get(),
                    "host capability failure must not retire verified encoded data");
        } finally {
            unsupported.representation().close();
        }

        var healthy = createContent();
        var cancellationReads = new AtomicInteger();
        var cancellationContent = new CountingContent(healthy, cancellationReads);
        var cancellationSound = healthy.modelFile().getCommon().sounds().get("tone_vorbis");
        var cancellationSource = new SoundSource(
                healthy.representation().identity(), cancellationSound);
        try (var runtime = new ClientAudioRuntime(Runnable::run,
                requested -> new ClientAudioRuntime.SoundAccess(cancellationContent, null))) {
            var cancelled = runtime.createPlayback(cancellationSource);
            var cancelledStream = cancelled.openStream(false).join();
            cancelled.stop();
            assertTrue(cancelledStream.isClosed());

            try (var retained = runtime.createPlayback(cancellationSource)
                    .openStream(false).join()) {
                assertFalse(retained.isClosed());
            }
            assertEquals(1, cancellationReads.get(),
                    "playback cancellation must not retire healthy encoded data");
        } finally {
            healthy.representation().close();
        }
    }

    @Test
    void stoppingRemotePlaybacksCancelsOnlyTheirAcceptedAcquisitions() throws Exception {
        var local = createContent();
        var workers = Executors.newSingleThreadExecutor();
        try (var store = new RemoteModelStore(temp.resolve("remote-store"))) {
            final RemoteModelContent remote;
            try (var prefix = local.representation().metadataPrefix().orElseThrow()) {
                remote = store.commitMetadataPrefix(
                        local.representation().identity(), prefix);
            }
            var starts = new LinkedBlockingQueue<CompletableFuture<Void>>();
            RemoteChunkFetcher fetcher = (identity, chunks, receiver) -> {
                var accepted = new CompletableFuture<Void>();
                starts.add(accepted);
                return accepted;
            };
            var sound = remote.modelFile().getCommon().sounds().get("tone_vorbis");
            var source = new SoundSource(remote.representation().identity(), sound);
            try (var runtime = new ClientAudioRuntime(workers,
                    requested -> new ClientAudioRuntime.SoundAccess(remote, fetcher))) {
                var first = runtime.createPlayback(source);
                var firstResult = first.openStream(false);
                var firstFetch = starts.poll(10, TimeUnit.SECONDS);
                assertTrue(firstFetch != null);

                var second = runtime.createPlayback(source);
                var secondResult = second.openStream(false);
                var secondFetch = starts.poll(10, TimeUnit.SECONDS);
                assertTrue(secondFetch != null);

                first.stop();
                assertTrue(firstFetch.isCancelled());
                assertFalse(secondFetch.isDone());
                assertThrows(CancellationException.class, firstResult::join);

                second.stop();
                assertTrue(secondFetch.isCancelled());
                assertThrows(CancellationException.class, secondResult::join);
            } finally {
                remote.representation().close();
            }
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            local.representation().close();
        }
    }

    @Test
    void stoppingWhileRemoteFetcherReturnsStillCancelsTheAcceptedAcquisition() throws Exception {
        var local = createContent();
        var workers = Executors.newSingleThreadExecutor();
        try (var store = new RemoteModelStore(temp.resolve("remote-store-race"))) {
            final RemoteModelContent remote;
            try (var prefix = local.representation().metadataPrefix().orElseThrow()) {
                remote = store.commitMetadataPrefix(
                        local.representation().identity(), prefix);
            }
            var enteredFetcher = new CountDownLatch(1);
            var returnFetcher = new CountDownLatch(1);
            var accepted = new CompletableFuture<Void>();
            var playbackRef = new AtomicReference<
                    AudioStreamProvider>();
            RemoteChunkFetcher fetcher = (identity, chunks, receiver) -> {
                enteredFetcher.countDown();
                try {
                    assertTrue(returnFetcher.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                playbackRef.get().stop();
                return accepted;
            };
            var sound = remote.modelFile().getCommon().sounds().get("tone_vorbis");
            var source = new SoundSource(remote.representation().identity(), sound);
            try (var runtime = new ClientAudioRuntime(workers,
                    requested -> new ClientAudioRuntime.SoundAccess(remote, fetcher))) {
                var playback = runtime.createPlayback(source);
                playbackRef.set(playback);
                var result = playback.openStream(false);
                assertTrue(enteredFetcher.await(10, TimeUnit.SECONDS));

                returnFetcher.countDown();
                workers.submit(() -> { }).get(10, TimeUnit.SECONDS);

                assertThrows(CancellationException.class, result::join);
                assertTrue(accepted.isCancelled(),
                        "an accepted transfer must not escape a stop racing with fetch return");
            } finally {
                remote.representation().close();
            }
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            local.representation().close();
        }
    }

    @Test
    void authorizationChangeDoesNotRejectAnAlreadyAcceptedRemoteAcquisition() throws Exception {
        var local = createContent();
        var workers = Executors.newSingleThreadExecutor();
        try (var store = new RemoteModelStore(temp.resolve("remote-store-authorization"))) {
            final RemoteModelContent remote;
            try (var prefix = local.representation().metadataPrefix().orElseThrow()) {
                remote = store.commitMetadataPrefix(
                        local.representation().identity(), prefix);
            }
            var accepted = new CompletableFuture<Void>();
            var transferStarted = new CompletableFuture<Void>();
            RemoteChunkFetcher fetcher = (identity, chunks, receiver) -> {
                try {
                    for (var chunk : chunks) {
                        try (var bytes = local.chunks().readStoredVerified(
                                chunk, BufferType.ARRAY)) {
                            receiver.accept(chunk, bytes);
                        }
                    }
                    transferStarted.complete(null);
                    return accepted;
                } catch (IOException failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            };
            var revoked = new AtomicBoolean();
            var sound = remote.modelFile().getCommon().sounds().get("tone_vorbis");
            var source = new SoundSource(remote.representation().identity(), sound);
            try (var runtime = new ClientAudioRuntime(workers, requested -> {
                if (revoked.get()) {
                    throw AssetLoadException.access(
                            "authorization changed after transfer acceptance");
                }
                return new ClientAudioRuntime.SoundAccess(remote, fetcher);
            })) {
                var result = runtime.createPlayback(source).openStream(false);
                transferStarted.get(10, TimeUnit.SECONDS);
                workers.submit(() -> { }).get(10, TimeUnit.SECONDS);

                revoked.set(true);
                accepted.complete(null);

                try (var stream = assertDoesNotThrow(result::join,
                        "request-time authorization must survive later revocation")) {
                    assertTrue(stream.read(4096).hasRemaining());
                }
            } finally {
                remote.representation().close();
            }
        } finally {
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            local.representation().close();
        }
    }

    private ManagedContainer createContent() throws Exception {
        return createContent(Files.readAllBytes(
                fixtureRoot().resolve("vorbis-under.ogg")));
    }

    private ManagedContainer createContent(byte[] encoded) throws Exception {
        var source = copyRawFixture(
                temp.resolve("raw-" + System.nanoTime()), encoded);
        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());
        Path container;
        try (var captured = importer.capture(source)) {
            container = importer.convert(captured,
                    Files.createDirectories(temp.resolve("output"))).stagedContainer();
        }
        return ManagedContainer.openDirect(container, new CatalogModelLocation(
                CatalogRootKind.CUSTOM, new ModelPath("audio-u3")));
    }

    private static Path copyRawFixture(Path destination, byte[] encoded) throws Exception {
        var manifest = ClientAudioRuntimeIntegrationTest.class.getResource(
                "/assets/ysm/builtin/misc/1_alex/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        var sounds = Files.createDirectories(destination.resolve("sounds"));
        Files.write(sounds.resolve("tone_vorbis.ogg"), encoded);
        return destination;
    }

    private static void assertDecoderContentFailure(
            CompletableFuture<CustomAudioStream> future)
            throws Exception {
        try {
            try (var stream = future.join()) {
                var failure = assertThrows(AssetLoadException.class,
                        () -> readAll(stream));
                assertEquals(AssetLoadException.Reason.CONTENT, failure.reason());
            }
        } catch (CompletionException failure) {
            var content = assertInstanceOf(AssetLoadException.class, failure.getCause());
            assertEquals(AssetLoadException.Reason.CONTENT, content.reason());
        }
    }

    private static void assertCapabilityFailure(
            CompletableFuture<CustomAudioStream> future) {
        var failure = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IOException.class, failure.getCause());
        assertFalse(failure.getCause() instanceof AssetLoadException);
    }

    private static byte[] corruptVorbisSetup(byte[] source) {
        var result = source.clone();
        int setup = find(result, new byte[]{5, 'v', 'o', 'r', 'b', 'i', 's'});
        if (setup < 0) {
            throw new IllegalArgumentException("Vorbis setup packet is missing");
        }
        result[setup + 20] ^= 0x40;
        rewritePageChecksum(result, pageContaining(result, setup));
        return result;
    }

    private static byte[] withVorbisSampleRate(byte[] source, long sampleRate) {
        var result = source.clone();
        int identification = find(
                result, new byte[]{1, 'v', 'o', 'r', 'b', 'i', 's'});
        if (identification < 0) {
            throw new IllegalArgumentException("Vorbis identification packet is missing");
        }
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(identification + 12, (int) sampleRate);
        rewritePageChecksum(result, pageContaining(result, identification));
        return result;
    }

    private static int find(byte[] bytes, byte[] pattern) {
        outer:
        for (int offset = 0; offset <= bytes.length - pattern.length; offset++) {
            for (int index = 0; index < pattern.length; index++) {
                if (bytes[offset + index] != pattern[index]) {
                    continue outer;
                }
            }
            return offset;
        }
        return -1;
    }

    private static int pageContaining(byte[] bytes, int target) {
        for (int offset = 0; offset < bytes.length; ) {
            int size = pageSize(bytes, offset);
            if (target < offset + size) {
                return offset;
            }
            offset += size;
        }
        throw new IllegalArgumentException("target is outside Ogg pages");
    }

    private static int pageSize(byte[] bytes, int offset) {
        int segments = Byte.toUnsignedInt(bytes[offset + 26]);
        int size = 27 + segments;
        for (int index = 0; index < segments; index++) {
            size += Byte.toUnsignedInt(bytes[offset + 27 + index]);
        }
        return size;
    }

    private static void rewritePageChecksum(byte[] bytes, int offset) {
        int size = pageSize(bytes, offset);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset + 22, 0);
        int crc = 0;
        for (int index = offset; index < offset + size; index++) {
            crc ^= Byte.toUnsignedInt(bytes[index]) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = crc << 1 ^ ((crc & 0x8000_0000) != 0 ? 0x04c1_1db7 : 0);
            }
        }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset + 22, crc);
    }

    private static byte[] readAll(CustomAudioStream stream)
            throws Exception {
        var output = new ByteArrayOutputStream();
        while (true) {
            var chunk = stream.read(4095);
            if (!chunk.hasRemaining()) {
                return output.toByteArray();
            }
            var bytes = new byte[chunk.remaining()];
            chunk.get(bytes);
            output.writeBytes(bytes);
        }
    }

    private static Path fixtureRoot() {
        return Path.of(System.getenv("YSM_AUDIO_FIXTURE_DIR"));
    }

    private record CountingContent(ModelContent delegate,
                                   AtomicInteger reads) implements ModelContent {
        @Override
        public ModelRepresentation representation() {
            return delegate.representation();
        }

        @Override
        public ChunkDataSource chunks() {
            return new ChunkDataSource() {
                @Override
                public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk,
                                             BufferType bufferType) throws IOException {
                    reads.incrementAndGet();
                    return delegate.chunks().readPayload(chunk, bufferType);
                }

                @Override
                public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk,
                                                    BufferType bufferType) throws IOException {
                    return delegate.chunks().readStoredVerified(chunk, bufferType);
                }
            };
        }
    }

    private record BlockingAfterReadContent(
            ModelContent delegate,
            CompletableFuture<Void> payloadRead,
            CompletableFuture<Void> allowReadReturn) implements ModelContent {
        @Override
        public ModelRepresentation representation() {
            return delegate.representation();
        }

        @Override
        public ChunkDataSource chunks() {
            return new ChunkDataSource() {
                @Override
                public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk,
                                             BufferType bufferType) throws IOException {
                    var payload = delegate.chunks().readPayload(chunk, bufferType);
                    payloadRead.complete(null);
                    try {
                        allowReadReturn.join();
                        return payload;
                    } catch (RuntimeException failure) {
                        payload.close();
                        throw failure;
                    }
                }

                @Override
                public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk,
                                                    BufferType bufferType) throws IOException {
                    return delegate.chunks().readStoredVerified(chunk, bufferType);
                }
            };
        }
    }
}
