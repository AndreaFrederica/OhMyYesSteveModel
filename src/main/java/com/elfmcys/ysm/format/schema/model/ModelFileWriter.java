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
import net.minecraftforge.fml.common.CertificateHelper;

public class ModelFileWriter extends AssetFileWriter {
    public ModelFileWriter() {
        setSchemaId(ModelFileConstant.SCHEMA_ID);
        setProperty(ModelFileConstant.PROP_VERSION, ModelFileConstant.CURRENT_VERSION.toString());
        if (YesSteveModel.MOD != null) {
            var cert = YesSteveModel.MOD.getSigningCertificate();
            var sign = cert != null ? CertificateHelper.getFingerprint(cert) : "(unsigned)";
            setProperty(ModelFileConstant.PROP_VENDOR, String.format("%s %s %s",
                    YesSteveModel.MOD.getModId(),
                    YesSteveModel.MOD.getVersion() != null ? YesSteveModel.MOD.getVersion() : "(unknown)",
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
