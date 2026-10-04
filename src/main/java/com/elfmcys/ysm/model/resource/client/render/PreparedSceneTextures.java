package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Worker-built material samples. Each distinct sampler has its own host texture binding. */
public final class PreparedSceneTextures {
    public record Sampler(int magFilter, int minFilter, int wrapS, int wrapT) {
        public Sampler {
            if (!Set.of(9728, 9729).contains(magFilter)
                    || !Set.of(9728, 9729, 9984, 9985, 9986, 9987).contains(minFilter)
                    || !Set.of(33071, 33648, 10497).contains(wrapS)
                    || !Set.of(33071, 33648, 10497).contains(wrapT)) {
                throw new IllegalArgumentException("Invalid scene texture sampler");
            }
        }
        public boolean mipmaps() { return minFilter >= 9984; }
    }

    public record Key(ScenePackageImages.Key image, Sampler sampler, SceneImageUsage usage) {
        public Key { Objects.requireNonNull(image); Objects.requireNonNull(sampler); Objects.requireNonNull(usage); }
    }
    public record Pixels(int width, int height, FloatData rgba, Sampler sampler) {
        public Pixels {
            Objects.requireNonNull(rgba); Objects.requireNonNull(sampler);
            if (width < 1 || height < 1 || (long) width * height * 4 != rgba.size()) {
                throw new IllegalArgumentException("Invalid scene texture pixels");
            }
        }
    }
    private final Map<Key, Pixels> textures;
    private PreparedSceneTextures(Map<Key, Pixels> textures) {
        this.textures = Collections.unmodifiableMap(new LinkedHashMap<>(textures));
    }
    public Map<Key, Pixels> textures() { return textures; }

    public static PreparedSceneTextures prepare(ScenePackageImages source, Collection<Key> requests,
                                                ReadLimits limits, BooleanSupplier cancelled) throws IOException {
        return prepare(source,requests,limits,cancelled,null);
    }
    public static PreparedSceneTextures prepare(ScenePackageImages source, Collection<Key> requests,
                                                ReadLimits limits, BooleanSupplier cancelled,cc.sirrus.ysmlib.SceneDiskCache.Session cache) throws IOException {
        var output = new LinkedHashMap<Key, Pixels>();
        var samples = new IdentityHashMap<SceneImage, Map<SceneImageUsage, FloatData>>();
        long bytes = 0;
        long pixels = 0;
        long gpuBytes = 0;
        for (var key : requests) {
            requireActive(cancelled);
            if (output.containsKey(key)) continue;
            if (output.size() >= limits.maxElements()) throw new IOException("Scene texture binding budget exceeded");
            var image = source.require(key.image());
            int mipWidth = image.width(), mipHeight = image.height();
            do {
                // Scene textures are uploaded as RGBA16F. RGBA32F doubled
                // the GPU footprint without improving the source formats used
                // by MMD/VRM/glTF, and made a valid multi-mip set exceed the
                // model budget before hardware LOD could be used.
                gpuBytes += (long) mipWidth * mipHeight * 8;
                if (gpuBytes > limits.maxBytes()) throw new IOException("Scene texture storage budget exceeded");
                if (!key.sampler().mipmaps() || mipWidth == 1 && mipHeight == 1) break;
                mipWidth = Math.max(1, mipWidth / 2);
                mipHeight = Math.max(1, mipHeight / 2);
            } while (true);
            var interpretations = samples.computeIfAbsent(image, ignored -> new HashMap<>());
            var data = interpretations.get(key.usage());
            if (data == null) {
                long remainingBytes = limits.maxBytes() - bytes;
                long remainingPixels = limits.maxElements() - pixels;
                if (remainingBytes < 1 || remainingPixels < 1) throw new IOException("Scene material sample budget exceeded");
                var remaining=new ReadLimits((int) remainingBytes, (int) remainingPixels,
                                (int) Math.min(remainingBytes, limits.maxStringBytes()));
                data = cache==null?YsmRuntime.scenes().texturePixels(image,key.usage(),remaining):cache.texturePixels(image,key.usage(),remaining);
                bytes += data.storageBytes();
                pixels += (long) image.width() * image.height();
                interpretations.put(key.usage(), data);
            }
            output.put(key, new Pixels(image.width(), image.height(), data, key.sampler()));
        }
        requireActive(cancelled);
        return new PreparedSceneTextures(output);
    }

    static void requireActive(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Scene texture publication was cancelled");
    }
}
