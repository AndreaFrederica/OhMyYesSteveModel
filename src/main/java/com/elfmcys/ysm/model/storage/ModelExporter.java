package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExportInfo;
import com.elfmcys.ysm.proto.mixel.manifest.info.Info;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** Produces one fully verified, atomically committed portable model artifact. */
public final class ModelExporter {
    private ModelExporter() {
    }

    public static Path export(ManagedContainer source, PreviewStore.EncodedPreview preview,
                              Path output, String extra) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(preview, "preview");
        output = Objects.requireNonNull(output, "output").toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        var temporary = output.resolveSibling(output.getFileName() + ".tmp-"
                + ProcessHandle.current().pid() + "-" + UUID.randomUUID());
        try {
            write(source, preview, temporary, extra);
            verify(source, temporary);
            AtomicSharedCache.moveCommitted(temporary, output);
            return output;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void write(ManagedContainer source, PreviewStore.EncodedPreview preview,
                              Path temporary, String extra) throws IOException {
        var view = source.modelFile();
        var thumbnail = view.getFileView().getAssetView().getChunkInfo(
                ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
        var preserveThumbnail = thumbnail != null
                && view.getThumbnailPreviewSource()
                != PreviewSource.PREVIEW_SOURCE_UNSPECIFIED;
        var info = Info.newBuilder(view.getManifest().info())
                .setExport(ExportInfo.newBuilder()
                        .setTimestamp(Instant.now().getEpochSecond())
                        .setVersion(version())
                        .setExtra(Objects.requireNonNullElse(extra, ""))
                        .build());
        if (!preserveThumbnail) {
            info.setThumbnailSource(PreviewSource.PREVIEW_SOURCE_GENERATED);
        }
        var manifest = com.elfmcys.ysm.proto.mixel.manifest.Manifest
                .newBuilder(view.getManifest()).setInfo(info.build()).build();
        try (var writer = new ModelFileWriter(view.schema())) {
            writer.setManifest(manifest);
            var chunks = view.getFileView().getAssetView().getChunkTable().values().stream()
                    .filter(chunk -> !chunk.type().equals(
                            AssetContainerConstant.VERIFICATION_CHUNK_TYPE))
                    .filter(chunk -> !chunk.type().equals(ModelFileConstant.MANIFEST_CHUNK_NAME))
                    .filter(chunk -> preserveThumbnail
                            || !chunk.type().equals(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME))
                    .sorted(Comparator.comparingInt(
                            AssetContainerView.ChunkInfo::offset))
                    .toList();
            for (var chunk : chunks) {
                try (var stored = source.chunks().readStoredVerified(chunk, BufferType.ARRAY)) {
                    writer.addStoredChunk(chunk, stored);
                }
            }
            if (!preserveThumbnail) {
                try (var image = preview.open()) {
                    writer.setThumbnail(image);
                }
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                writer.write(channel);
            }
        }
    }

    private static void verify(ManagedContainer source, Path temporary) throws IOException {
        var checked = ManagedContainer.openDirect(temporary, source.location());
        try {
            if (!checked.representation().modelId().equals(source.representation().modelId())) {
                throw new IOException("Export changed the model identity");
            }
            if (checked.modelFile().schema() != source.modelFile().schema()) {
                throw new IOException("Export changed the model schema");
            }
            if (checked.modelFile().schema() == com.elfmcys.ysm.format.schema.model.ModelSchema.GENERAL_MESH) {
                for (var target : checked.modelFile().getRenderTargets()) {
                    target.readScenePackage(() -> false, checked.chunks(), cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT);
                }
            }
            DirectContainerAdmission.requireEmbeddedPreview(checked);
            checked.modelFile().getCommon().validateSoundContent(
                    () -> false, checked.chunks());
        } finally {
            checked.representation().close();
        }
    }

    private static String version() {
        return YesSteveModel.MOD == null || YesSteveModel.MOD.getModInfo().getVersion() == null
                ? "dev" : YesSteveModel.MOD.getModInfo().getVersion().toString();
    }
}
