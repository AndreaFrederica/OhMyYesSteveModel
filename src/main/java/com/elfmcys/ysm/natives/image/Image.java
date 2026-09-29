package com.elfmcys.ysm.natives.image;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.image.ImageProvider;
import com.elfmcys.ysm.mixin.client.NativeImageAccessor;
import com.elfmcys.ysm.util.ScopeGuard;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceLists;

import java.io.UnsupportedEncodingException;
import java.io.IOException;
import org.lwjgl.system.MemoryUtil;
import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public record Image(Format format, int width, int height, UniBuffer data) implements AutoCloseable {
    public Image share() {
        return new Image(format, width, height, data.acquire());
    }

    @SuppressWarnings("DataFlowIssue")
    public NativeImage decode() throws UnsupportedEncodingException {
        byte[] pixels = decodedPixels();
        try (var dstScope = ScopeGuard.create(new NativeImage(NativeImage.Format.RGBA, width, height, false))) {
            var dst = dstScope.get();
            var dstAccessor = (NativeImageAccessor) (Object) dst;
            try {
                MemoryUtil.memByteBuffer(dstAccessor.ysm$pixels(), pixels.length).put(pixels);
            } finally {
                Reference.reachabilityFence(dst);
            }
            return dstScope.release();
        }
    }

    public NativeBuffer decodeToBuffer() throws UnsupportedEncodingException {
        byte[] pixels = decodedPixels();
        try (var dstScope = NativeBuffer.allocateWithScope(pixels.length)) {
            var dst = dstScope.get();
            try {
                dst.nio().put(pixels);
            } finally {
                Reference.reachabilityFence(dst);
            }
            return dstScope.release();
        }
    }

    public static Image probe(UniBuffer buffer) throws UnsupportedEncodingException {
        try {
            var info = YsmRuntime.images().probe(buffer.nio());
            return new Image(Format.valueOf(info.format().name()), info.width(), info.height(), buffer.acquire());
        } catch (IOException invalid) {
            throw decodeFailure(invalid);
        } finally {
            Reference.reachabilityFence(buffer);
        }
    }

    private byte[] decodedPixels() throws UnsupportedEncodingException {
        try {
            return YsmRuntime.images().decode(data.nio(), new ImageProvider.Info(
                    ImageProvider.Format.valueOf(format.name()), width, height));
        } catch (IOException | IllegalArgumentException invalid) {
            throw decodeFailure(invalid);
        } finally { Reference.reachabilityFence(data); }
    }

    private static UnsupportedEncodingException decodeFailure(Exception cause) {
        return (UnsupportedEncodingException) new UnsupportedEncodingException("Failed to decode image: "
                + cause.getMessage()).initCause(cause);
    }

    @Override
    public void close() {
        data.close();
    }

    public enum Format {
        RGBA(0),
        PNG(1),
        JPEG(2),
        WEBP(3),
        AVIF(4),
        ZTX(5);

        private final int id;

        Format(int value) {
            id = value;
        }

        public int id() {
            return id;
        }

        public static final List<Format> VALUES = ReferenceLists.unmodifiable(ReferenceArrayList.wrap(
                Arrays.stream(Format.values()).sorted(Comparator.comparingInt(f -> f.id)).toArray(Format[]::new)));
    }
}
