package com.elfmcys.ysm.natives.legacy;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.legacy.LegacyImportProvider;
import cc.sirrus.ysmlib.legacy.V3EnvelopeProvider;
import cc.sirrus.ysmlib.v3d.V3dCache;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.source.*;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LegacyConversionReuseTest {
    @TempDir Path temp;
    private final AtomicInteger decodes = new AtomicInteger();
    private final AtomicInteger projections = new AtomicInteger();

    private LegacyModelImporter importer(int envelopeProfile, int projectionProfile) {
        var envelope = new V3EnvelopeProvider() {
            public int profile() { return envelopeProfile; }
            public byte[] decode(ByteBuffer source, int limit) throws IOException {
                decodes.incrementAndGet();
                return Files.readAllBytes(Path.of("runtime/java-v3/src/test/resources/historical/v32.wire"));
            }
        };
        var projection = new LegacyImportProvider() {
            public int profile() { return projectionProfile; }
            public Bundle importModel(ByteBuffer source) { throw new AssertionError("Must use V3D wire"); }
            public Bundle importWire(byte[] wire, long size) throws IOException {
                projections.incrementAndGet();
                return YsmRuntime.legacy().importWire(wire, size);
            }
        };
        return new LegacyModelImporter(projection, new V3dCache(envelope));
    }

    private ModelSourceResolver resolver(String version, int envelope, int projection) {
        return resolver(version, importer(envelope, projection));
    }

    private ModelSourceResolver resolver(String version, LegacyModelImporter importer) {
        var cache = temp.resolve("cache");
        return new ModelSourceResolver(new RawModelImporter(DefaultAnimationFilter.keepAll()),
                importer, new ConvertedSourceIndexStore(cache, version),
                new ConvertedObjectStore(cache));
    }

    private SourceObservation observe(Path source) throws IOException {
        var root = new CatalogRootIdentity(CatalogRootKind.CUSTOM, source.getParent(), "test");
        var key = new ModelSourceKey(root, new ModelPath(source.getFileName().toString()),
                ModelSourceKind.LEGACY_ARCHIVE);
        return new SourceObservation(key, source, SourceStamp.captureFile(source), Files.size(source));
    }

    private Path load(ModelSourceResolver resolver, Path source) throws Exception {
        var result = resolver.resolveMaterialized(observe(source));
        try {
            assertEquals(result.entry().identity(), result.content().representation().identity());
            assertEquals(result.entry().identity(), result.convertedIndexEntry().orElseThrow().identity());
            return result.entry().backingFile();
        } finally { result.content().representation().close(); }
    }

    private Path source() throws IOException { return Files.write(temp.resolve("model.ysm"), new byte[]{1, 2, 3}); }

    @Test void cacheOnlyProbeNeverConvertsAMissOrDamagedObject() throws Exception {
        var source = source();
        var resolver = resolver("test", 1, 1);
        assertTrue(resolver.resolveCached(observe(source)).isEmpty());
        assertEquals(0, decodes.get());
        assertEquals(0, projections.get());
        var object = load(resolver, source);
        var hit = resolver.resolveCached(observe(source)).orElseThrow();
        hit.content().representation().close();
        assertEquals(1, decodes.get());
        assertEquals(1, projections.get());
        var bytes = Files.readAllBytes(object);
        bytes[bytes.length - 1] ^= 1;
        Files.write(object, bytes);
        assertTrue(resolver.resolveCached(observe(source)).isEmpty());
        assertEquals(1, projections.get(), "Damaged cache must wait for cold admission");
        load(resolver, source);
        assertEquals(1, decodes.get(), "Cold rebuild still reuses V3D wire");
        assertEquals(2, projections.get());
        var stamp = Files.getLastModifiedTime(source);
        Files.write(source, new byte[]{3, 2, 1});
        Files.setLastModifiedTime(source, stamp);
        assertTrue(resolver.resolveCached(observe(source)).isEmpty());
        assertEquals(1, decodes.get(), "Source hash mismatch must wait for cold admission too");
    }

    @Test void restartAndSourceRenameReuseWithoutDecodeOrProjection() throws Exception {
        var source = source();
        var object = load(resolver("test", 1, 1), source);
        var modified = Files.getLastModifiedTime(object);
        assertEquals(object, load(resolver("test", 1, 1), source));
        var renamed = Files.move(source, temp.resolve("renamed.ysm"));
        assertEquals(object, load(resolver("test", 1, 1), renamed));
        assertEquals(modified, Files.getLastModifiedTime(object));
        assertEquals(1, decodes.get());
        assertEquals(1, projections.get());
    }

    @Test void sameSizeSameTimestampSourceEditInvalidatesTheReceipt() throws Exception {
        var source = source();
        var stamp = Files.getLastModifiedTime(source);
        load(resolver("test", 1, 1), source);
        Files.write(source, new byte[]{3, 2, 1});
        Files.setLastModifiedTime(source, stamp);
        load(resolver("test", 1, 1), source);
        assertEquals(2, decodes.get());
        assertEquals(2, projections.get());
    }

    @Test void damagedConvertedBodyRebuildsFromWireWithoutEnvelopeDecode() throws Exception {
        var source = source();
        var object = load(resolver("test", 1, 1), source);
        var original = Files.readAllBytes(object);
        var stamp = Files.getLastModifiedTime(object);
        var changed = original.clone();
        changed[changed.length - 1] ^= 1;
        Files.write(object, changed);
        Files.setLastModifiedTime(object, stamp);
        load(resolver("test", 1, 1), source);
        assertArrayEquals(original, Files.readAllBytes(object));
        assertEquals(1, decodes.get());
        assertEquals(2, projections.get());
    }

    @Test void missingObjectAndCorruptReceiptAreRecoverable() throws Exception {
        var source = source();
        var object = load(resolver("test", 1, 1), source);
        Files.delete(object);
        load(resolver("test", 1, 1), source);
        try (var files = Files.list(temp.resolve("cache/legacy/converted"))) {
            Files.write(files.findFirst().orElseThrow(), new byte[]{1, 2});
        }
        load(resolver("test", 1, 1), source);
        assertEquals(1, decodes.get());
        assertEquals(3, projections.get());
    }

    @Test void versionAndProjectorInvalidateConversionButReuseWire() throws Exception {
        var source = source();
        load(resolver("test", 1, 1), source);
        load(resolver("next-version", 1, 1), source);
        load(resolver("next-version", 1, 2), source);
        assertEquals(1, decodes.get());
        assertEquals(3, projections.get());
        load(resolver("next-version", 2, 2), source);
        assertEquals(2, decodes.get());
        assertEquals(4, projections.get());
    }

    @Test void corruptWireIsRebuiltWhenConversionIsNeeded() throws Exception {
        var source = source();
        Files.delete(load(resolver("test", 1, 1), source));
        try (var files = Files.walk(temp.resolve("cache/legacy/v3d"))) {
            Files.write(files.filter(p -> p.getFileName().toString().equals("wire.bin"))
                    .findFirst().orElseThrow(), new byte[]{1, 2, 3});
        }
        load(resolver("test", 1, 1), source);
        assertEquals(2, decodes.get());
        assertEquals(2, projections.get());
    }

    @Test void concurrentOwnersOnlyConvertOnce() throws Exception {
        var source = source();
        var first = resolver("test", 1, 1);
        var second = resolver("test", 1, 1);
        var a = CompletableFuture.supplyAsync(() -> uncheckedLoad(first, source));
        var b = CompletableFuture.supplyAsync(() -> uncheckedLoad(second, source));
        assertEquals(a.get(), b.get());
        assertEquals(1, decodes.get());
        assertEquals(1, projections.get());
    }

    private Path uncheckedLoad(ModelSourceResolver resolver, Path source) {
        try { return load(resolver, source); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "YSM_LEGACY_CACHE_SAMPLE", matches = ".+")
    void realModelColdAndWarmLoads() throws Exception {
        var source = Path.of(System.getenv("YSM_LEGACY_CACHE_SAMPLE"));
        var envelope = new V3EnvelopeProvider() {
            public int profile() { return YsmRuntime.v3().profile(); }
            public byte[] decode(ByteBuffer input, int limit) throws IOException {
                decodes.incrementAndGet();
                return YsmRuntime.v3().decode(input, limit);
            }
        };
        var projection = new LegacyImportProvider() {
            public Bundle importModel(ByteBuffer input) { throw new AssertionError("Must use V3D"); }
            public Bundle importWire(byte[] wire, long size) throws IOException {
                projections.incrementAndGet();
                return YsmRuntime.legacy().importWire(wire, size);
            }
        };
        var importer = new LegacyModelImporter(projection, new V3dCache(envelope));
        long start = System.nanoTime();
        var object = load(resolver("test", importer), source);
        long cold = System.nanoTime() - start;
        start = System.nanoTime();
        assertEquals(object, load(resolver("test", importer), source));
        long warm = System.nanoTime() - start;
        assertEquals(1, decodes.get());
        assertEquals(1, projections.get());
        var expected = Files.readAllBytes(object);
        Files.delete(object);
        start = System.nanoTime();
        load(resolver("test", importer), source);
        long wireReuse = System.nanoTime() - start;
        assertArrayEquals(expected, Files.readAllBytes(object));
        assertEquals(1, decodes.get());
        assertEquals(2, projections.get());
        System.out.printf("Legacy cache benchmark: source=%d bytes, object=%d bytes, cold=%.1f ms, warm=%.1f ms, wire-rebuild=%.1f ms%n",
                Files.size(source), expected.length, cold / 1e6, warm / 1e6, wireReuse / 1e6);
    }
}
