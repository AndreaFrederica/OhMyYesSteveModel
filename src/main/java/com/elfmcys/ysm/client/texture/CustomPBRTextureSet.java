package com.elfmcys.ysm.client.texture;

import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.info.type.PBRTextureType;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate;
import com.elfmcys.ysm.natives.image.ImageSource;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import org.jetbrains.annotations.Nullable;

/** Target-bound texture objects whose loads complete synchronously on the render owner. */
public final class CustomPBRTextureSet extends AbstractTexture implements PBRTextureSet {
    private final ImageSource source;
    private final ModelResourceFailureGate failureGate;
    private final TextureIO textureIO;
    private final @Nullable ComponentTexture normal;
    private final @Nullable ComponentTexture specular;
    private final Map<PBRTextureType, AbstractTexture> pbrTextures;
    private @Nullable BufferedImage prepared;

    public CustomPBRTextureSet(PBRImageSources sources,
                               BufferedImage uv, @Nullable BufferedImage normal,
                               @Nullable BufferedImage specular,
                               ModelResourceFailureGate uvFailure,
                               ModelResourceFailureGate normalFailure,
                               ModelResourceFailureGate specularFailure) {
        this(sources, uv, normal, specular, uvFailure, normalFailure,
                specularFailure, TextureIO.PRODUCTION);
    }

    CustomPBRTextureSet(PBRImageSources sources,
                        BufferedImage uv, @Nullable BufferedImage normal,
                        @Nullable BufferedImage specular,
                        ModelResourceFailureGate uvFailure,
                        ModelResourceFailureGate normalFailure,
                        ModelResourceFailureGate specularFailure,
                        TextureIO textureIO) {
        Objects.requireNonNull(sources, "sources");
        source = sources.uv();
        prepared = Objects.requireNonNull(uv, "uv");
        failureGate = Objects.requireNonNull(uvFailure, "uvFailure");
        this.textureIO = Objects.requireNonNull(textureIO, "textureIO");
        this.normal = sources.normal() == null ? null
                : new ComponentTexture(sources.normal(), Objects.requireNonNull(normal, "normal"),
                Objects.requireNonNull(normalFailure, "normalFailure"), textureIO);
        this.specular = sources.specular() == null ? null
                : new ComponentTexture(sources.specular(), Objects.requireNonNull(specular, "specular"),
                Objects.requireNonNull(specularFailure, "specularFailure"), textureIO);
        var textures = new EnumMap<PBRTextureType, AbstractTexture>(PBRTextureType.class);
        if (this.normal != null) {
            textures.put(PBRTextureType.NORMAL, this.normal);
        }
        if (this.specular != null) {
            textures.put(PBRTextureType.SPECULAR, this.specular);
        }
        pbrTextures = Collections.unmodifiableMap(textures);
    }

    @Override
    public void loadTexture(IResourceManager resourceManager) throws IOException {
        var pixels = prepared;
        prepared = null;
        load(source, pixels, failureGate, this, textureIO);
    }

    public void loadPbr(IResourceManager resourceManager) throws IOException {
        if (normal != null) {
            normal.loadTexture(resourceManager);
        }
        if (specular != null) {
            specular.loadTexture(resourceManager);
        }
    }

    public @Nullable AbstractTexture getNormal() {
        return normal;
    }

    public @Nullable AbstractTexture getSpecular() {
        return specular;
    }

    @Override
    public Map<PBRTextureType, ? extends AbstractTexture> getPBRTextures() {
        return pbrTextures;
    }

    /** Releases objects that have not crossed a validated host ownership boundary. */
    public void closeUnregistered() {
        closeUnregistered(List.of());
    }

    public void closeUnregistered(Collection<? extends AbstractTexture> hostOwned) {
        if (normal != null && !hostOwned.contains(normal)) {
            normal.close();
        }
        if (specular != null && !hostOwned.contains(specular)) {
            specular.close();
        }
        if (!hostOwned.contains(this)) {
            close();
        }
    }

    /** 1.12.2 的 AbstractTexture 没有 close()，这里等价于上游 close()：释放像素并删除 GL 纹理。 */
    public void close() {
        prepared = close(prepared, textureIO);
        deleteGlTexture();
    }

    private static void load(ImageSource source, @Nullable BufferedImage prepared,
                             ModelResourceFailureGate failureGate,
                             AbstractTexture texture,
                             TextureIO textureIO) throws IOException {
        var previous = failureGate.failure().orElse(null);
        if (previous != null) {
            var failure = new IOException("Model texture component previously failed", previous);
            try {
                close(prepared, textureIO);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        BufferedImage pixels = prepared;
        try {
            if (pixels == null) {
                try (var image = source.open()) {
                    pixels = image.decode();
                }
            }
            textureIO.upload(texture, pixels);
        } catch (IOException | RuntimeException error) {
            failureGate.fail(error);
            if (error instanceof IOException io) {
                throw io;
            }
            throw error;
        } finally {
            close(pixels, textureIO);
        }
    }

    private static @Nullable BufferedImage close(@Nullable BufferedImage image, TextureIO textureIO) {
        if (image != null) {
            textureIO.close(image);
        }
        return null;
    }

    @FunctionalInterface
    interface TextureIO {
        TextureIO PRODUCTION = (texture, pixels) ->
                TextureUtil.uploadTextureImageAllocate(texture.getGlTextureId(), pixels, false, false);

        void upload(AbstractTexture texture, BufferedImage pixels);

        default void close(BufferedImage pixels) {
            // BufferedImage 是堆对象，无需显式释放
        }
    }

    private static final class ComponentTexture extends AbstractTexture {
        private final ImageSource source;
        private final ModelResourceFailureGate failureGate;
        private final TextureIO textureIO;
        private @Nullable BufferedImage prepared;

        private ComponentTexture(ImageSource source, @Nullable BufferedImage prepared,
                                 ModelResourceFailureGate failureGate,
                                 TextureIO textureIO) {
            this.source = source;
            this.prepared = prepared;
            this.failureGate = failureGate;
            this.textureIO = textureIO;
        }

        @Override
        public void loadTexture(IResourceManager resourceManager) throws IOException {
            var pixels = prepared;
            prepared = null;
            CustomPBRTextureSet.load(source, pixels, failureGate, this, textureIO);
        }

        /** 1.12.2 的 AbstractTexture 没有 close()，这里等价于上游 close()。 */
        public void close() {
            prepared = CustomPBRTextureSet.close(prepared, textureIO);
            deleteGlTexture();
        }
    }
}
