package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerReader;
import com.elfmcys.ysm.format.container.AssetContainerWriter;
import com.elfmcys.ysm.format.schema.file.AssetFileWriter;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.mixel.common.Image;
import com.elfmcys.ysm.proto.mixel.manifest.Manifest;
import com.elfmcys.ysm.proto.mixel.manifest.asset.Common;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSet;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTarget;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.Info;
import com.elfmcys.ysm.proto.mixel.manifest.info.License;
import com.elfmcys.ysm.proto.mixel.manifest.info.Metadata;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettings;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStats;
import com.elfmcys.ysm.proto.mixel.manifest.info.Properties;
import com.elfmcys.ysm.proto.mixel.manifest.info.Settings;
import com.elfmcys.ysm.util.ProtoUtil;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import us.hebi.quickbuf.UninitializedMessageException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelFileIdentityReaderTest {
    @TempDir
    Path temp;

    @Test
    void writerDerivesExactIdentityPropertyFromManifest() throws Exception {
        var modelId = hash(1);
        var file = writeWithProductionWriter("round-trip.mxc", manifest(modelId));

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var identity = ModelFileIdentityReader.read(channel);
            assertEquals(modelId, identity.modelId());
            assertEquals(modelId.toString(), AssetContainerReader.read(channel)
                    .getSchemaProperty(ModelFileConstant.PROP_MODEL_ID));
        }
    }

    @Test
    void rejectsPreviousUnstableModelSchema() throws Exception {
        var modelId = hash(2);
        var file = writePreviousVersion("previous-version.mxc", modelId,
                manifest(modelId));

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThrows(UnsupportedEncodingException.class,
                    () -> ModelFileIdentityReader.read(channel));
        }
    }

    @Test
    void strictBuildRejectsMissingManifestAndWriterRejectsWrongLengthIdentity() throws Exception {
        assertThrows(UninitializedMessageException.class,
                () -> Manifest.newBuilder().build());
        var wrongLength = manifest(ByteBuffer.wrap(new byte[31]));
        try (var writer = new ModelFileWriter()) {
            assertThrows(IOException.class, () -> writer.setManifest(wrongLength));
        }
    }

    @Test
    void rejectsMissingMalformedOrNonLowercaseIdentityProperty() throws Exception {
        var modelId = hash(0xab);
        for (var property : new String[]{null, "0".repeat(63), modelId.toString().toUpperCase()}) {
            var file = writeContainer("invalid-" + String.valueOf(property).hashCode() + ".mxc",
                    property, manifest(modelId));
            try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
                assertThrows(IOException.class, () -> ModelFileIdentityReader.read(channel));
            }
        }
    }

    @Test
    void fullViewRejectsPropertyManifestMismatch() throws Exception {
        var file = writeContainer("mismatch.mxc", hash(3).toString(), manifest(hash(4)));

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertThrows(IOException.class, () -> new ModelFileView(channel));
        }
    }

    @Test
    void identityPropertyParticipatesInContainerIdentity() throws Exception {
        var manifest = manifest(hash(6));
        var first = writeContainer("identity-a.mxc", hash(6).toString(), manifest);
        var second = writeContainer("identity-b.mxc", hash(7).toString(), manifest);

        try (var firstChannel = FileChannel.open(first, StandardOpenOption.READ);
             var secondChannel = FileChannel.open(second, StandardOpenOption.READ)) {
            assertNotEquals(AssetContainerReader.read(firstChannel).getContainerId(),
                    AssetContainerReader.read(secondChannel).getContainerId());
        }
    }

    @Test
    void identityScanDoesNotReadManifestPayload() throws Exception {
        var modelId = hash(5);
        var file = writeWithProductionWriter("tampered-manifest.mxc", manifest(modelId));
        final int manifestOffset;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            manifestOffset = AssetContainerReader.read(channel)
                    .getChunkInfo(ModelFileConstant.MANIFEST_CHUNK_NAME).offset();
        }
        var bytes = Files.readAllBytes(file);
        bytes[manifestOffset] ^= 1;
        Files.write(file, bytes);

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            assertEquals(modelId, ModelFileIdentityReader.read(channel).modelId());
        }
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var failure = assertThrows(AssetLoadException.class,
                    () -> new ModelFileView(channel));
            assertEquals(AssetLoadException.Reason.CONTENT, failure.reason());
        }
    }

    @Test
    void preservesAccessFailureProvenance() throws Exception {
        var file = writeWithProductionWriter("access.mxc", manifest(hash(8)));
        try (var channel = new FailingReadChannel(
                FileChannel.open(file, StandardOpenOption.READ))) {
            var failure = assertThrows(AssetLoadException.class,
                    () -> ModelFileIdentityReader.read(channel));
            assertEquals(AssetLoadException.Reason.ACCESS, failure.reason());
        }
    }

    @Test
    void metadataLayoutRequiresTheManifestToBeFirstDirectAndNonEmpty() throws Exception {
        var modelId = hash(9);
        var valid = writeWithProductionWriter("layout-valid.mxc", manifest(modelId));
        try (var channel = FileChannel.open(valid, StandardOpenOption.READ)) {
            ModelFileView.requireMetadataLayout(AssetContainerReader.read(channel));
        }
        for (var mode : InvalidLayout.values()) {
            final Path invalid;
            try (var writer = new InvalidLayoutWriter(modelId, mode)) {
                invalid = write("layout-" + mode + ".mxc", writer);
            }
            try (var channel = FileChannel.open(invalid, StandardOpenOption.READ)) {
                var asset = AssetContainerReader.read(channel);
                assertThrows(IOException.class, () ->
                        ModelFileView.requireMetadataLayout(asset));
            }
        }
    }

    @Test
    void metadataPrefixIncludesLegalAlignmentPadding() throws Exception {
        var modelId = hash(10);
        var file = writeAlignedModel("aligned-prefix.mxc", modelId, manifest(modelId));
        final int prefixSize;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var asset = AssetContainerReader.read(channel);
            var manifest = ModelFileView.requireMetadataLayout(asset);
            assertNotEquals(0, manifest.alignSize());
            prefixSize = Math.addExact(manifest.offset(), manifest.size());
        }
        var fileBytes = Files.readAllBytes(file);
        assertEquals(prefixSize, fileBytes.length);
    }

    @Test
    void productionReopenAcceptsTexturelessProjectileAndVehicle() throws Exception {
        var modelId = hash(11);
        var manifest = manifest(modelId).toBuilder()
                .addRenderTargets(renderTarget(
                        "player",
                        RenderTargetKind.RENDER_TARGET_KIND_PLAYER,
                        "main"))
                .addRenderTargets(renderTarget(
                        "projectile-1",
                        RenderTargetKind.RENDER_TARGET_KIND_PROJECTILE,
                        null))
                .addRenderTargets(renderTarget(
                        "vehicle-1",
                        RenderTargetKind.RENDER_TARGET_KIND_VEHICLE,
                        null))
                .build();
        var file = writeWithProductionWriter("textureless-non-player.mxc", manifest);

        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var view = new ModelFileView(channel);
            assertEquals(0, view.requireRenderTarget("projectile-1")
                    .getTextureNames().size());
            assertEquals(0, view.requireRenderTarget("vehicle-1")
                    .getTextureNames().size());
        }
    }

    @Test
    void productionReopenRejectsEmptyTextureKeyForEveryTargetKind() throws Exception {
        var kinds = new RenderTargetKind[]{
                RenderTargetKind.RENDER_TARGET_KIND_PLAYER,
                RenderTargetKind.RENDER_TARGET_KIND_PROJECTILE,
                RenderTargetKind.RENDER_TARGET_KIND_VEHICLE,
        };
        for (int index = 0; index < kinds.length; ++index) {
            var kind = kinds[index];
            var modelId = hash(12 + index);
            var manifest = manifest(modelId).toBuilder();
            if (kind != RenderTargetKind.RENDER_TARGET_KIND_PLAYER) {
                manifest.addRenderTargets(renderTarget(
                        "player",
                        RenderTargetKind.RENDER_TARGET_KIND_PLAYER,
                        "main"));
            }
            manifest.addRenderTargets(renderTarget(
                    kind == RenderTargetKind.RENDER_TARGET_KIND_PLAYER
                            ? "player" : "target-1",
                    kind, ""));
            var file = writeWithProductionWriter(
                    "empty-texture-key-" + index + ".mxc", manifest.build());

            try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
                assertThrows(IOException.class, () -> new ModelFileView(channel));
            }
        }
    }

    @Test
    void explicitMeshSchemaUsesAClosedPackageAndCannotBeReadAsMcDefinition() throws Exception {
        var source = new cc.sirrus.ysmlib.scene.ScenePackage(
                new cc.sirrus.ysmlib.scene.ScenePackage.Source("model", "avatar.gltf",
                        cc.sirrus.ysmlib.scene.ScenePackage.Format.GLTF),
                new cc.sirrus.ysmlib.scene.ScenePackage.Settings(1, 0,
                        cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),
                java.util.List.of(), java.util.List.of(), java.util.Map.of("avatar.gltf",
                        new cc.sirrus.ysmlib.scene.ByteData("{\"asset\":{\"version\":\"2.0\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        var limits = cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT;
        var encoded = cc.sirrus.ysmlib.YsmRuntime.scenes().writePackage(source, limits);
        var metadata = manifest(hash(33)).toBuilder()
                .addRenderTargets(renderTarget("player", RenderTargetKind.RENDER_TARGET_KIND_PLAYER, null).toBuilder().setBlobId(1).build()).build();
        final Path file;
        try (var writer = new ModelFileWriter(ModelSchema.GENERAL_MESH);
             var data = ArrayBuffer.borrow(encoded.copy())) {
            writer.setManifest(metadata);
            assertEquals(1, writer.addBlob(data, 0));
            file = write("mesh.mxc", writer);
        }
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var view = new ModelFileView(channel);
            var chunks = new com.elfmcys.ysm.format.schema.file.FileChunkDataSource(file);
            assertEquals(ModelSchema.GENERAL_MESH, view.schema());
            assertEquals(com.elfmcys.ysm.model.domain.RenderTargetIds.GENERAL_MESH_VARIANT,
                    ModelManifestLookup.chooseTexture(view, "player", "previous-mc-texture"));
            var request = new com.elfmcys.ysm.model.resource.client.ResourceRequest(hash(33), "player", "previous-mc-texture",
                    new com.elfmcys.ysm.model.resource.client.BakeProfile("test"));
            assertEquals(com.elfmcys.ysm.model.domain.RenderTargetIds.GENERAL_MESH_VARIANT,
                    com.elfmcys.ysm.model.resource.client.ClientModelRenderTargetManager
                            .resolveEffectiveKey(view, request).selectedTexture());
            assertEquals(hash(33), ModelFileIdentityReader.read(channel).modelId());
            assertEquals(source, view.getPlayer().readScenePackage(() -> false, chunks, limits));
            assertThrows(IOException.class, () -> view.getPlayer().readDefinition(() -> false, chunks));
            assertThrows(IOException.class, () -> view.getPlayer().readScenePackage(() -> false, chunks,
                    new cc.sirrus.ysmlib.scene.io.ReadLimits(32, 10, 10)));
            var descriptor = ModelFileView.requireMetadataLayout(view.getFileView().getAssetView());
            var prefix = java.util.Arrays.copyOf(Files.readAllBytes(file), descriptor.offset() + descriptor.size());
            try (var bytes = ArrayBuffer.borrow(prefix)) {
                assertEquals(ModelSchema.GENERAL_MESH, ModelFileView.readMetadata(bytes).schema());
                try (var store = new com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore(temp.resolve("mesh-remote"))) {
                    var remote = store.commitMetadataPrefix(ModelFileIdentityReader.read(channel), bytes);
                    try {
                        var downloads = new java.util.concurrent.atomic.AtomicInteger();
                        com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher fetcher = (identity, requested, receiver) -> {
                            assertEquals(java.util.List.of("blob-1"), requested.stream().map(c -> c.type()).toList());
                            downloads.incrementAndGet();
                            try {
                                for (var chunk : requested) {
                                    try (var stored = chunks.readStoredVerified(chunk, com.elfmcys.ysm.buffer.BufferType.ARRAY)) {
                                        receiver.accept(chunk, stored);
                                    }
                                }
                                return java.util.concurrent.CompletableFuture.completedFuture(null);
                            } catch (IOException error) {
                                return java.util.concurrent.CompletableFuture.failedFuture(error);
                            }
                        };
                        java.util.function.Function<com.elfmcys.ysm.model.catalog.content.ModelContent, cc.sirrus.ysmlib.scene.ScenePackage> load = content -> {
                            try { return content.modelFile().getPlayer().readScenePackage(() -> false, content.chunks(), limits); }
                            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                        };
                        assertEquals(source, remote.loadRenderTarget(() -> false, "player", "previous-mc-texture", fetcher, Runnable::run, load).join());
                        assertEquals(source, remote.loadRenderTarget(() -> false, "player", "", fetcher, Runnable::run, load).join());
                        assertEquals(1, downloads.get());
                    } finally {
                        remote.representation().close();
                    }
                }
            }
        }
        var mc = writeWithProductionWriter("mesh-under-mc.mxc", metadata);
        try (var channel = FileChannel.open(mc, StandardOpenOption.READ)) {
            assertThrows(IOException.class, () -> new ModelFileView(channel));
        }
        var textured = manifest(hash(34)).toBuilder()
                .addRenderTargets(renderTarget("player", RenderTargetKind.RENDER_TARGET_KIND_PLAYER, "main")).build();
        assertThrows(IOException.class, () -> ModelFileView.validatePlayerRenderTarget(textured, ModelSchema.GENERAL_MESH));
        var location = new com.elfmcys.ysm.model.catalog.source.CatalogModelLocation(
                com.elfmcys.ysm.model.catalog.source.CatalogRootKind.CUSTOM,
                new com.elfmcys.ysm.model.domain.ModelPath("mesh.mxc"));
        var original = com.elfmcys.ysm.model.storage.ManagedContainer.openDirect(file, location);
        try {
            var preview = com.elfmcys.ysm.model.storage.TestPreviews.blank();
            var output = temp.resolve("mesh-export.mxc");
            com.elfmcys.ysm.model.storage.ModelExporter.export(original, preview, output, "schema-test");
            var exported = com.elfmcys.ysm.model.storage.ManagedContainer.openDirect(output, location);
            try {
                assertEquals(ModelSchema.GENERAL_MESH, exported.modelFile().schema());
                assertEquals(original.representation().modelId(), exported.representation().modelId());
                assertEquals(source, exported.modelFile().getPlayer().readScenePackage(() -> false, exported.chunks(), limits));
            } finally {
                exported.representation().close();
            }
        } finally {
            original.representation().close();
        }
    }

    private Path writeWithProductionWriter(String name,
                                           Manifest manifest)
            throws Exception {
        try (var writer = new ModelFileWriter()) {
            writer.setManifest(manifest);
            return write(name, writer);
        }
    }

    private Path writeAlignedModel(String name, Hash256 modelId,
                                   Manifest manifest)
            throws Exception {
        var output = new ByteArrayOutputStream();
        try (var data = ArrayBuffer.allocate(manifest.getSerializedSize());
             var writer = new AssetContainerWriter();
             var channel = Channels.newChannel(output)) {
            manifest.writeTo(ProtoUtil.sink(data));
            writer.setSchema(ModelFileConstant.SCHEMA_ID);
            writer.setSchemaProperty(ModelFileConstant.PROP_VERSION,
                    ModelFileConstant.CURRENT_VERSION.toString());
            writer.setSchemaProperty(ModelFileConstant.PROP_VENDOR, "test");
            writer.setSchemaProperty(ModelFileConstant.PROP_MODEL_ID,
                    modelId.toString());
            writer.addChunk(ModelFileConstant.MANIFEST_CHUNK_NAME,
                    "", 0, 8, 0, data, 0);
            writer.write(channel);
        }
        return Files.write(temp.resolve(name), output.toByteArray());
    }

    private Path writeContainer(String name, String property,
                                Manifest manifest) throws Exception {
        try (var writer = new TestModelWriter(property, manifest)) {
            return write(name, writer);
        }
    }

    private Path writePreviousVersion(
            String name, Hash256 modelId,
            Manifest manifest) throws Exception {
        try (var writer = new PreviousVersionWriter(modelId, manifest)) {
            return write(name, writer);
        }
    }

    private Path write(String name, AssetFileWriter writer) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var channel = Channels.newChannel(output)) {
            writer.write(channel);
        }
        return Files.write(temp.resolve(name), output.toByteArray());
    }

    private static Manifest manifest(Hash256 modelId) {
        return manifest(ByteBuffer.wrap(modelId.bytes()));
    }

    private static Manifest manifest(ByteBuffer modelId) {
        return Manifest.newBuilder()
                .setCommonAssets(Common.newBuilder()
                        .setStringsBlobId(0)
                        .build())
                .setInfo(Info.newBuilder()
                        .setSettings(Settings.newBuilder()
                                .setDefaultTexture("")
                                .setPreviewAnimation("")
                                .setDisablePreviewRotation(false)
                                .build())
                        .setMetadata(Metadata.newBuilder()
                                .setName("")
                                .setTips("")
                                .setLicense(License.newBuilder()
                                        .setType("")
                                        .setDesc("")
                                        .build())
                                .build())
                        .setProperties(Properties.newBuilder()
                                .setModelId(modelId)
                                .setFree(false).setOriginVer("").build())
                        .build())
                .build();
    }

    private static RenderTarget renderTarget(
            String targetId, RenderTargetKind kind,
            String textureKey) {
        var target = RenderTarget.newBuilder()
                .setTargetId(targetId)
                .setKind(kind)
                .setBlobId(0)
                .setSettings(ModelSettings.newBuilder()
                        .setHeightScale(0)
                        .setWidthScale(0)
                        .setRenderLayersFirst(false)
                        .setForceCulling(false)
                        .setGuiNoLighting(false)
                        .setMergeMultilineExpr(false)
                        .build())
                .setStats(ModelStats.newBuilder()
                        .setBones(0)
                        .setCubes(0)
                        .setFaces(0)
                        .build());
        if (textureKey != null) {
            target.putTextures(textureKey, PBRTextureSet.newBuilder()
                    .setUv(emptyImage()).build());
        }
        return target.build();
    }

    private static Image emptyImage() {
        return Image.newBuilder()
                .setBlobId(0).setFormat("").setWidth(0).setHeight(0).setFrameCount(0)
                .build();
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte) seed;
        return new Hash256(bytes);
    }

    private static final class TestModelWriter extends AssetFileWriter {
        private TestModelWriter(String modelIdProperty,
                                Manifest manifest) throws IOException {
            setSchemaId(ModelFileConstant.SCHEMA_ID);
            setProperty(ModelFileConstant.PROP_VERSION,
                    ModelFileConstant.CURRENT_VERSION.toString());
            setProperty(ModelFileConstant.PROP_VENDOR, "test");
            if (modelIdProperty != null) {
                setProperty(ModelFileConstant.PROP_MODEL_ID, modelIdProperty);
            }
            addProtoChunk(ModelFileConstant.MANIFEST_CHUNK_NAME, manifest, 0);
        }
    }

    private static final class PreviousVersionWriter extends AssetFileWriter {
        private PreviousVersionWriter(
                Hash256 modelId,
                Manifest manifest) throws IOException {
            setSchemaId(ModelFileConstant.SCHEMA_ID);
            setProperty(ModelFileConstant.PROP_VERSION, "0.2.0-unstable");
            setProperty(ModelFileConstant.PROP_VENDOR, "test");
            setProperty(ModelFileConstant.PROP_MODEL_ID, modelId.toString());
            addProtoChunk(ModelFileConstant.MANIFEST_CHUNK_NAME, manifest, 0);
        }
    }

    private static final class InvalidLayoutWriter extends AssetFileWriter {
        private InvalidLayoutWriter(Hash256 modelId, InvalidLayout mode) throws IOException {
            setSchemaId(ModelFileConstant.SCHEMA_ID);
            setProperty(ModelFileConstant.PROP_VERSION,
                    ModelFileConstant.CURRENT_VERSION.toString());
            setProperty(ModelFileConstant.PROP_VENDOR, "test");
            setProperty(ModelFileConstant.PROP_MODEL_ID, modelId.toString());
            if (mode == InvalidLayout.BEFORE_MANIFEST) {
                addRawChunk("before-manifest", ArrayBuffer.borrow(new byte[]{1}), 0);
            }
            if (mode == InvalidLayout.EMPTY) {
                addRawChunk(ModelFileConstant.MANIFEST_CHUNK_NAME,
                        ArrayBuffer.borrow(new byte[0]), 0);
            } else {
                addProtoChunk(ModelFileConstant.MANIFEST_CHUNK_NAME, manifest(modelId),
                        mode == InvalidLayout.COMPRESSED ? 1 : 0);
            }
        }
    }

    private enum InvalidLayout {
        BEFORE_MANIFEST,
        COMPRESSED,
        EMPTY
    }

    private static final class FailingReadChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;

        private FailingReadChannel(SeekableByteChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            throw new IOException("offline");
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long position) throws IOException {
            delegate.position(position);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
