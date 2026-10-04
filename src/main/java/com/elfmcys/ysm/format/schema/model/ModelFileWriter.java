package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.schema.file.AssetFileWriter;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.proto.mixel.manifest.Manifest;
import com.elfmcys.ysm.util.ProtoBytes;
import java.io.IOException;
import java.util.Objects;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;

public class ModelFileWriter extends AssetFileWriter {
    public ModelFileWriter() {
        this(ModelSchema.MC);
    }

    public ModelFileWriter(ModelSchema schema) {
        setSchemaId(Objects.requireNonNull(schema, "schema").id());
        setProperty(ModelFileConstant.PROP_VERSION, schema.version());
        if (YesSteveModel.MOD != null) {
            var sign = ((ModFileInfo) YesSteveModel.MOD.getModInfo().getOwningFile()).getCodeSigningFingerprint().orElse("(unsigned)");
            var modInfo = YesSteveModel.MOD.getModInfo();
            setProperty(ModelFileConstant.PROP_VENDOR, String.format("%s %s %s",
                    modInfo.getModId(),
                    modInfo.getVersion() != null ? modInfo.getVersion().toString() : "(unknown)",
                    sign));
        } else {
            setProperty(ModelFileConstant.PROP_VENDOR, "dev");
        }
    }

    public void setManifest(Manifest manifest) throws IOException {
        setManifestProperties(manifest);
        addProtoChunk(ModelFileConstant.MANIFEST_CHUNK_NAME, manifest, 0);
    }

    public void setManifestExact(Manifest manifest,
                                 UniBuffer logicalBytes) throws IOException {
        setManifestProperties(manifest);
        addRawChunk(ModelFileConstant.MANIFEST_CHUNK_NAME,
                Objects.requireNonNull(logicalBytes, "logicalBytes"), 0);
    }

    private void setManifestProperties(Manifest manifest)
            throws IOException {
        var properties = manifest.info().properties();
        if (properties.modelId().remaining() != Hash256.SIZE) {
            throw new IOException("Manifest contains no valid full model hash");
        }
        var hash = properties.modelId();
        setProperty(ModelFileConstant.PROP_MODEL_ID,
                new Hash256(ProtoBytes.copy(hash)).toString());
        generateSummary(manifest);
    }

    private void generateSummary(Manifest manifest) {
        if (manifest.info().hasMetadata()) {
            setSummary(manifest.info().metadataUnsafe().name());
        } else {
            setSummary("");
        }
    }

    public void setThumbnail(Image img) {
        addImage(ModelFileConstant.THUMB_BUTTON_CHUNK_NAME, img);
    }

    public void setIcon(Image img) {
        addImage(ModelFileConstant.THUMB_ICON_CHUNK_NAME, img);
    }
}
