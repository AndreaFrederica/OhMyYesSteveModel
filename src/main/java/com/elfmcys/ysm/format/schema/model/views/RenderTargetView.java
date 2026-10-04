package com.elfmcys.ysm.format.schema.model.views;

import com.elfmcys.ysm.format.schema.file.AssetFileView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelData;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSet;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTarget;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Read-only access to one independently distributable render target. */
public final class RenderTargetView {
    private final AssetFileView fileView;
    private final RenderTarget descriptor;
    private final Map<String, PBRTextureSet> textures;

    public RenderTargetView(AssetFileView fileView, RenderTarget descriptor) {
        this.fileView = fileView;
        this.descriptor = descriptor;
        var values = new LinkedHashMap<String, PBRTextureSet>();
        if (!descriptor.textures().isEmpty()) {
            descriptor.textures().forEach(values::put);
        }
        this.textures = Map.copyOf(values);
    }

    public String id() {
        return descriptor.targetId();
    }

    public RenderTargetKind kind() {
        return descriptor.kind();
    }

    public List<String> matches() {
        var values = new ArrayList<String>();
        if (!descriptor.match().isEmpty()) {
            descriptor.match().forEach(values::add);
        }
        return List.copyOf(values);
    }

    public Set<String> getTextureNames() {
        return textures.keySet();
    }

    public PBRTextureSet textureDescriptor(String name) throws FileNotFoundException {
        var value = textures.get(name);
        if (value == null) {
            throw new FileNotFoundException("Render target " + id() + " contains no texture named " + name);
        }
        return value;
    }

    public ModelData readDefinition(BooleanSupplier cancelled,
                                                        ChunkDataSource source) throws IOException {
        requireSchema(com.elfmcys.ysm.format.schema.model.ModelSchema.MC);
        return fileView.readProtoBlob(cancelled, source, descriptor.blobId(),
                ModelData::parseFrom);
    }

    public cc.sirrus.ysmlib.scene.ScenePackage readScenePackage(BooleanSupplier cancelled,
            ChunkDataSource source, cc.sirrus.ysmlib.scene.io.ReadLimits limits) throws IOException {
        requireSchema(com.elfmcys.ysm.format.schema.model.ModelSchema.GENERAL_MESH);
        var chunk = fileView.getAssetView().getChunkInfo("blob-" + descriptor.blobId());
        if (chunk == null) throw new FileNotFoundException("Missing scene package blob " + descriptor.blobId());
        long logicalBytes = chunk.encoding().isEmpty() ? chunk.size() : chunk.decodeSize();
        if (logicalBytes < 0 || logicalBytes > limits.maxBytes()) {
            throw new IOException("Scene package blob exceeds byte limit");
        }
        try (var data = fileView.readBlobPayload(cancelled, source, descriptor.blobId(), com.elfmcys.ysm.buffer.BufferType.ARRAY)) {
            if (data.size() > limits.maxBytes()) throw new IOException("Scene package blob exceeds byte limit");
            byte[] bytes = new byte[data.size()];data.nio().get(bytes);
            return cc.sirrus.ysmlib.YsmRuntime.scenes().readPackage(new cc.sirrus.ysmlib.scene.ByteData(bytes), limits);
        }
    }

    private void requireSchema(com.elfmcys.ysm.format.schema.model.ModelSchema expected) throws IOException {
        if (!fileView.getAssetView().getSchema().equals(expected.id())) {
            throw new IOException("Render target definition requires " + expected.id());
        }
    }

    public PBRImageSources textureSources(ChunkDataSource source, String textureName)
            throws IOException {
        return fileView.textureSources(source, textureDescriptor(textureName));
    }

    public PBRImageSources textureSources(BooleanSupplier cancelled, ChunkDataSource source,
                                          String textureName) throws IOException {
        return fileView.textureSources(cancelled, source, textureDescriptor(textureName));
    }

    public RenderTarget descriptor() {
        return descriptor;
    }
}
