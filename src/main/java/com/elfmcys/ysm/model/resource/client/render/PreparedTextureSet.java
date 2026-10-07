package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.model.resource.client.ModelResourceFailureGate;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.client.texture.CustomPBRTextureSet;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.natives.image.ImageSource;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** One candidate's decoded model texture set before host publication. */
final class PreparedTextureSet implements BakedModelCache.TexturePixelsSupplier, AutoCloseable {
    private final PBRImageSources sources;
    private final ModelResourceFailureGate uvFailure;
    private final ModelResourceFailureGate normalFailure;
    private final ModelResourceFailureGate specularFailure;
    private final Decoder decoder;
    private BufferedImage uv;
    private BufferedImage normal;
    private BufferedImage specular;
    private NativeBuffer uvPixels;
    private int uvWidth;
    private int uvHeight;
    private boolean transferred;

    PreparedTextureSet(PBRImageSources sources, String resourcePrefix,
                       ModelResourceFailures failures) {
        this(sources, resourcePrefix, failures, PreparedTextureSet::decodeSource);
    }

    PreparedTextureSet(PBRImageSources sources, String resourcePrefix,
                       ModelResourceFailures failures, Decoder decoder) {
        this.sources = Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(resourcePrefix, "resourcePrefix");
        Objects.requireNonNull(failures, "failures");
        uvFailure = failures.texture(resourcePrefix + "uv");
        normalFailure = failures.texture(resourcePrefix + "normal");
        specularFailure = failures.texture(resourcePrefix + "specular");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    @Override
    public BakedModelCache.TexturePixels get() throws IOException {
        requireOwned();
        if (uvPixels == null) {
            var image = decode(sources.uv(), uvFailure, "uv");
            uvWidth = image.getWidth();
            uvHeight = image.getHeight();
            uvPixels = toPixels(image);
            uv = image;
        }
        return new BakedModelCache.TexturePixels(uvPixels, uvWidth, uvHeight);
    }

    void prepare(BooleanSupplier cancelled) throws IOException {
        requireActive(cancelled);
        get();
        requireActive(cancelled);
        if (sources.normal() != null) {
            normal = decode(sources.normal(), normalFailure, "normal");
            requireActive(cancelled);
        }
        if (sources.specular() != null) {
            specular = decode(sources.specular(), specularFailure, "specular");
            requireActive(cancelled);
        }
    }

    CustomPBRTextureSet transfer() {
        requireOwned();
        if (uv == null || (sources.normal() != null && normal == null)
                || (sources.specular() != null && specular == null)) {
            throw new IllegalStateException("Model texture set is not completely prepared");
        }
        var result = new CustomPBRTextureSet(sources, uv, normal, specular,
                uvFailure, normalFailure, specularFailure);
        uv = null;
        normal = null;
        specular = null;
        if (uvPixels != null) {
            uvPixels.close();
            uvPixels = null;
        }
        transferred = true;
        return result;
    }

    private BufferedImage decode(ImageSource source, ModelResourceFailureGate failureGate,
                                 String component) throws IOException {
        var previous = failureGate.failure().orElse(null);
        if (previous != null) {
            throw AssetLoadException.content(
                    "Model texture component previously failed: " + component, previous);
        }
        try {
            return decoder.decode(source);
        } catch (IOException | RuntimeException error) {
            failureGate.fail(error);
            if (error instanceof IOException io) {
                throw AssetLoadException.content(
                        "Failed to decode model texture component: " + component, io);
            }
            throw error;
        }
    }

    private static BufferedImage decodeSource(ImageSource source) throws IOException {
        try (var image = source.open()) {
            return image.decode();
        }
    }

    /** 转成烘焙器需要的 RGBA 原生缓冲（ABGR int → RGBA byte），尺寸取自图像本身。 */
    private static NativeBuffer toPixels(BufferedImage image) {
        var width = image.getWidth();
        var height = image.getHeight();
        var argb = image.getRGB(0, 0, width, height, null, 0, width);
        var pixels = NativeBuffer.allocate(width * height * 4);
        var out = pixels.nio();
        try {
            for (var value : argb) {
                out.put((byte) (value & 0xFF));
                out.put((byte) ((value >>> 8) & 0xFF));
                out.put((byte) ((value >>> 16) & 0xFF));
                out.put((byte) (value >>> 24));
            }
            out.flip();
        } catch (RuntimeException | Error error) {
            pixels.close();
            throw error;
        }
        return pixels;
    }

    private void requireOwned() {
        if (transferred) {
            throw new IllegalStateException("Prepared model texture set was already transferred");
        }
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Model texture preparation was cancelled");
        }
    }

    @Override
    public void close() {
        // BufferedImage 是堆对象，无需显式释放；烘焙器用的原生缓冲在这里回收
        uv = null;
        normal = null;
        specular = null;
        if (uvPixels != null) {
            uvPixels.close();
            uvPixels = null;
        }
    }

    @FunctionalInterface
    interface Decoder {
        BufferedImage decode(ImageSource source) throws IOException;
    }
}
