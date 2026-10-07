package com.elfmcys.ysm.natives.image;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.natives.buffer.BufferArgument;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceLists;

import java.awt.image.BufferedImage;
import java.io.UnsupportedEncodingException;
import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public record Image(Format format, int width, int height, UniBuffer data) implements AutoCloseable {
    private Image(long packedInfo, UniBuffer buffer) {
        this(Format.VALUES.get((int) (packedInfo >>> 32) & 0xFF),
                (int) (packedInfo >>> 16) & 0xFFFF,
                (int) (packedInfo & 0xFFFF),
                buffer);
    }

    public Image share() {
        return new Image(format, width, height, data.acquire());
    }

    // 1.12.2 没有 NativeImage，解码到原生 RGBA 缓冲后转成 TYPE_INT_ARGB 的 BufferedImage
    public BufferedImage decode() throws UnsupportedEncodingException {
        try (var pixels = decodeToBuffer()) {
            var src = pixels.nio();
            var argb = new int[width * height];
            for (var i = 0; i < argb.length; ++i) {
                var offset = i * 4;
                argb[i] = (src.get(offset + 3) & 0xFF) << 24
                        | (src.get(offset) & 0xFF) << 16
                        | (src.get(offset + 1) & 0xFF) << 8
                        | (src.get(offset + 2) & 0xFF);
            }
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, width, height, argb, 0, width);
            return image;
        }
    }

    public NativeBuffer decodeToBuffer() throws UnsupportedEncodingException {
        var args = BufferArgument.packInput(data);
        try (var dstScope = NativeBuffer.allocateWithScope(width * height * 4)) {
            var dst = dstScope.get();
            final boolean result;
            try {
                result = Native.nDecode(args.obj(), args.flags(),
                        format.id, width, height, dst.ptr(), dst.size());
            } finally {
                Reference.reachabilityFence(data);
                Reference.reachabilityFence(dst);
            }
            if (result) {
                return dstScope.release();
            }
        }
        throw new UnsupportedEncodingException("Failed to decode image");
    }

    public static Image probe(UniBuffer buffer) throws UnsupportedEncodingException {
        var args = BufferArgument.packInput(buffer);
        final long result;
        try {
            result = Native.nProbe(args.obj(), args.flags());
        } finally {
            Reference.reachabilityFence(buffer);
        }
        if (result == 0) {
            throw new UnsupportedEncodingException("Failed to read image");
        }
        return new Image(result, buffer.acquire());
    }

    @Override
    public void close() {
        data.close();
    }

    private static class Native {
        private static native long nProbe(Object input, long input_flags);
        private static native boolean nDecode(Object input, long input_flags,
                                              int format, int width, int height,
                                              long dst, long dst_size);
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
