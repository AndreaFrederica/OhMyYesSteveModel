package com.elfmcys.ysm.natives.image;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import cc.sirrus.ysmlib.YsmRuntime;
import org.lwjgl.system.MemoryUtil;
import com.elfmcys.ysm.mixin.client.NativeImageAccessor;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.lang.ref.Reference;

public class ImageEncoder {
    public static Image encodeLossy(NativeBuffer pixels, int width, int height, int maxWidth, int maxHeight) throws IOException {
        validatePixelBuffer(pixels.size(), width, height);
        return encode(pixels.ptr(), pixels, width, height, false, maxWidth, maxHeight);
    }

    public static Image encodeLossless(NativeBuffer pixels, int width, int height) throws IOException {
        validatePixelBuffer(pixels.size(), width, height);
        return encode(pixels.ptr(), pixels, width, height, true, 0, 0);
    }

    public static Image encodeLossy(NativeImage image, int maxWidth, int maxHeight) throws IOException {
        if (image.format() != NativeImage.Format.RGBA) {
            throw new UnsupportedOperationException("Image format not supported");
        }
        var accessor = (NativeImageAccessor) (Object) image;
        validatePixelBuffer(accessor.ysm$size(), image.getWidth(), image.getHeight());
        return encode(accessor.ysm$pixels(), image, image.getWidth(), image.getHeight(),
                false, maxWidth, maxHeight);
    }

    public static Image encodeLossless(NativeImage image) throws IOException {
        if (image.format() != NativeImage.Format.RGBA) {
            throw new UnsupportedOperationException("Image format not supported");
        }
        var accessor = (NativeImageAccessor) (Object) image;
        validatePixelBuffer(accessor.ysm$size(), image.getWidth(), image.getHeight());
        return encode(accessor.ysm$pixels(), image, image.getWidth(), image.getHeight(),
                true, 0, 0);
    }

    private static Image encode(long pixels, Object pixelsOwner, int width, int height,
                                boolean lossless, int maxWidth, int maxHeight) throws IOException {
        var capacity = Math.toIntExact(requiredPixelBytes(width, height));
        try {
            var encoded = YsmRuntime.images().encode(MemoryUtil.memByteBuffer(pixels, capacity), width, height,
                    lossless ? width : maxWidth, lossless ? height : maxHeight);
            var info = encoded.info();
            return new Image(Image.Format.valueOf(info.format().name()), info.width(), info.height(),
                    ArrayBuffer.move(encoded.bytes()));
        } finally { Reference.reachabilityFence(pixelsOwner); }
    }

    private static void validatePixelBuffer(long size, int width, int height) {
        if (size < requiredPixelBytes(width, height)) {
            throw new IllegalArgumentException("Illegal pixels buffer size");
        }
    }

    private static long requiredPixelBytes(int width, int height) {
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535
                || (long) width * height * 4 > com.elfmcys.ysm.buffer.UniBuffer.MAX_SIZE) {
            throw new IllegalArgumentException("Invalid image dimensions");
        }
        return Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
    }

}
