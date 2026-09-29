package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogModelLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.domain.ModelScanWarning;
import com.elfmcys.ysm.model.storage.ConvertedObjectStore;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndexStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelExporter;
import com.elfmcys.ysm.model.storage.TestPreviews;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogReconcilerTest {
    @TempDir
    static Path fixtureTemp;

    @TempDir
    Path temp;

    private static Path defaultFixture;
    private static Path candidateFixture;
    private static Path candidateWithoutPreviewFixture;

    @BeforeAll
    static void createFixture() throws Exception {
        var defaultManifest = CatalogReconcilerTest.class.getResource(
                "/assets/ysm/builtin/default/ysm.json");
        var defaultSource = Path.of(Objects.requireNonNull(
                defaultManifest).toURI()).getParent();
        try (var vfs = new Directory(defaultSource)) {
            defaultFixture = ModelParser.parseBuiltinDefault(vfs,
                    Files.createDirectories(fixtureTemp.resolve("default")));
        }
        var candidateManifest = CatalogReconcilerTest.class.getResource(
                "/assets/ysm/builtin/misc/1_alex/ysm.json");
        var candidateSource = Path.of(Objects.requireNonNull(
                candidateManifest).toURI()).getParent();
        try (var vfs = new Directory(candidateSource)) {
            var parsed = ModelParser.parse(vfs,
                    Files.createDirectories(fixtureTemp.resolve("candidate")),
                    DefaultAnimationFilter.keepAll());
            candidateWithoutPreviewFixture = parsed;
            var location = new CatalogModelLocation(CatalogRootKind.CUSTOM,
                    new ModelPath("candidate"));
            var source = ManagedContainer.openDirect(parsed, location);
            try {
                candidateFixture = ModelExporter.export(source,
                        TestPreviews.blank(),
                        fixtureTemp.resolve("candidate-with-preview.mxc"), null);
            } finally {
                source.representation().close();
            }
        }
    }

    @Test
    void firstFullyValidCandidateWinsForTheSameModelId() throws Exception {
        var root = Files.createDirectories(temp.resolve("custom"));
        Files.copy(candidateFixture, root.resolve("a.mxc"));
        Files.copy(candidateFixture, root.resolve("b.mxc"));

        var candidate = reconciler(root).reconcile();
        var modelId = identity(candidateFixture).modelId();

        assertEquals(2, candidate.index().entries().size());
        assertEquals(2, candidate.snapshot().byModelId().size());
        assertEquals("a.mxc", candidate.snapshot().byModelId().get(modelId)
                .location().path().value());
        assertEquals(0, candidate.snapshot().report().errorCount());
    }

    @Test
    void invalidCandidateIsOmittedAndLaterValidCandidateWins() throws Exception {
        var root = Files.createDirectories(temp.resolve("fallback"));
        var broken = Files.copy(candidateFixture, root.resolve("a.mxc"));
        var bytes = Files.readAllBytes(broken);
        bytes[bytes.length - 1] ^= 1;
        Files.write(broken, bytes);
        Files.copy(candidateFixture, root.resolve("b.mxc"));

        var candidate = reconciler(root).reconcile();
        var identity = identity(candidateFixture);

        assertEquals(1, candidate.index().findCandidates(identity).size());
        assertEquals("b.mxc", candidate.snapshot().byModelId().get(identity.modelId())
                .location().path().value());
        assertEquals(1, candidate.snapshot().report().errorCount());
    }

    @Test
    void invalidModelIsOmittedWithoutDiscardingTheCandidate() throws Exception {
        var root = Files.createDirectories(temp.resolve("invalid"));
        Files.write(root.resolve("broken.mxc"),
                AssetContainerConstant.HEAD);

        var candidate = reconciler(root).reconcile();

        assertTrue(candidate.index().entries().isEmpty());
        assertEquals(1, candidate.snapshot().byModelId().size());
        assertEquals(1, candidate.snapshot().report().errorCount());
    }

    @Test
    void rawUnknownAudioWarningSurvivesSuccessfulPublication() throws Exception {
        var root = Files.createDirectories(temp.resolve("raw-warning"));
        var manifest = CatalogReconcilerTest.class.getResource(
                "/assets/ysm/builtin/misc/1_alex/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI())
                .getParent();
        var raw = root.resolve("raw-model");
        copyTree(source, raw);
        Files.createDirectories(raw.resolve("sounds"));
        Files.write(raw.resolve("sounds/unknown.mp3"), new byte[]{1, 2, 3});

        var candidate = reconciler(root).reconcile();

        assertEquals(0, candidate.snapshot().report().errorCount());
        assertEquals(1, candidate.snapshot().report().warningCount());
        assertEquals(1, candidate.snapshot().report().warnings().size());
        assertEquals(ModelScanWarning.Kind.UNKNOWN_AUDIO,
                candidate.snapshot().report().warnings().get(0).kind());
    }

    @Test
    void rawInvalidSupportedAudioHasADistinctWarning() throws Exception {
        var root = Files.createDirectories(temp.resolve("raw-invalid-audio"));
        var manifest = CatalogReconcilerTest.class.getResource(
                "/assets/ysm/builtin/wine_fox/14_momo/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI())
                .getParent();
        copyTree(source, root.resolve("raw-model"));

        // The builtin Ogg streams are valid, including legal EOS trimming.
        // Damage one supported stream explicitly instead of relying on a
        // previous decoder's rejection of a valid builtin file.
        var sound = root.resolve("raw-model/sounds/dooble.ogg");
        var corrupt = Files.readAllBytes(sound);
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(sound, corrupt);

        var candidate = reconciler(root).reconcile();

        assertEquals(0, candidate.snapshot().report().errorCount());
        assertEquals(1, candidate.snapshot().report().warningCount());
        assertEquals(ModelScanWarning.Kind.INVALID_AUDIO,
                candidate.snapshot().report().warnings().get(0).kind());
    }

    @Test
    void directContainerWithoutEmbeddedPreviewIsRejected() throws Exception {
        var root = Files.createDirectories(temp.resolve("missing-preview"));
        Files.copy(candidateWithoutPreviewFixture, root.resolve("missing.mxc"));

        var candidate = reconciler(root).reconcile();

        assertTrue(candidate.index().entries().isEmpty());
        assertEquals(1, candidate.snapshot().byModelId().size());
        assertEquals(1, candidate.snapshot().report().errorCount());
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var files = Files.walk(source)) {
            for (var path : files.toList()) {
                var destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }

    private CatalogReconciler reconciler(Path root) throws Exception {
        var cacheRoot = temp.resolve("cache");
        var resolver = new ModelSourceResolver(new RawModelImporter(
                DefaultAnimationFilter.keepAll()),
                new ConvertedSourceIndexStore(cacheRoot, "test-version"),
                new ConvertedObjectStore(cacheRoot));
        var intrinsicDefault = openIndexed(defaultFixture,
                new CatalogModelLocation(CatalogRootKind.BUILTIN,
                        new ModelPath("default")));
        return new CatalogReconciler(List.of(new ModelCatalogSource(
                CatalogRootKind.CUSTOM, root, false)), resolver,
                Set.of(intrinsicDefault.modelId()), intrinsicDefault,
                CatalogIndexSnapshot.empty());
    }

    private static ManagedContainer openIndexed(Path file, CatalogModelLocation location)
            throws Exception {
        var identity = identity(file);
        return ManagedContainer.openIndexed(new CatalogIndexEntry(
                identity, location, file));
    }

    private static CatalogIndexSnapshot index(
            ModelFileIdentity identity,
                                              Path file, String path) {
        return new CatalogIndexSnapshot(List.of(new CatalogIndexEntry(
                identity,
                new CatalogModelLocation(CatalogRootKind.CUSTOM,
                        new ModelPath(path)), file)),
                List.of(), ModelScanReport.empty());
    }

    private static ModelFileIdentity identity(Path file)
            throws Exception {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return ModelFileIdentityReader.read(channel);
        }
    }
}
