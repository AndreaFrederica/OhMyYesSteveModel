package com.elfmcys.ysm.buffer;

import io.netty.util.internal.PlatformDependent;
import org.lwjgl.system.MemoryUtil;

import java.lang.ref.Reference;
import java.nio.ByteBuffer;

public final class UniBufferIO {
    private UniBufferIO() {
    }

    public static void copy(UniBuffer source, int sourceOffset,
                            UniBuffer target, int targetOffset, int length) {
        try {
            checkRange(source.size(), sourceOffset, length);
            checkRange(target.size(), targetOffset, length);
            if (length == 0) {
                return;
            }
            if (source instanceof ArrayBuffer sourceArray) {
                var sourceIndex = sourceArray.arrayOffset() + sourceOffset;
                if (target instanceof ArrayBuffer targetArray) {
                    System.arraycopy(sourceArray.array(), sourceIndex,
                            targetArray.array(), targetArray.arrayOffset() + targetOffset, length);
                } else {
                    var targetBuffer = target.nio();
                    targetBuffer.position(targetOffset);
                    targetBuffer.put(sourceArray.array(), sourceIndex, length);
                }
                return;
            }
            var sourceAddress = ((NativeBuffer) source).ptr() + sourceOffset;
            if (target instanceof ArrayBuffer targetArray) {
                var sourceBuffer = MemoryUtil.memByteBuffer(sourceAddress, length);
                var targetIndex = targetArray.arrayOffset() + targetOffset;
                ByteBuffer.wrap(targetArray.array(), targetIndex, length).put(sourceBuffer);
            } else {
                MemoryUtil.memCopy(sourceAddress,
                        ((NativeBuffer) target).ptr() + targetOffset, length);
            }
        } finally {
            Reference.reachabilityFence(source);
            Reference.reachabilityFence(target);
        }
    }

    public static boolean equals(UniBuffer source, int sourceOffset,
                                 byte[] target, int targetOffset, int length) {
        try {
            checkRange(source.size(), sourceOffset, length);
            checkRange(target.length, targetOffset, length);
            if (source instanceof ArrayBuffer array) {
                var sourceIndex = array.arrayOffset() + sourceOffset;
                for (var index = 0; index < length; index++) {
                    if (array.array()[sourceIndex + index] != target[targetOffset + index]) {
                        return false;
                    }
                }
                return true;
            }
            var address = ((NativeBuffer) source).ptr() + sourceOffset;
            for (var index = 0; index < length; index++) {
                if (PlatformDependent.getByte(address + index) != target[targetOffset + index]) {
                    return false;
                }
            }
            return true;
        } finally {
            Reference.reachabilityFence(source);
            Reference.reachabilityFence(target);
        }
    }

    public static boolean equals(UniBuffer left, int leftOffset,
                                 UniBuffer right, int rightOffset, int length) {
        try {
            checkRange(left.size(), leftOffset, length);
            checkRange(right.size(), rightOffset, length);
            for (var index = 0; index < length; index++) {
                if (get(left, leftOffset + index) != get(right, rightOffset + index)) {
                    return false;
                }
            }
            return true;
        } finally {
            Reference.reachabilityFence(left);
            Reference.reachabilityFence(right);
        }
    }

    private static byte get(UniBuffer buffer, int index) {
        if (buffer instanceof ArrayBuffer array) {
            return array.array()[array.arrayOffset() + index];
        }
        return PlatformDependent.getByte(((NativeBuffer) buffer).ptr() + index);
    }

    private static void checkRange(int size, int offset, int length) {
        if (offset < 0 || length < 0 || offset > size - length) {
            throw new IndexOutOfBoundsException();
        }
    }
}
