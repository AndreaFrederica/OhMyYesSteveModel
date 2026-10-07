package com.elfmcys.ysm.natives.render;

import com.elfmcys.ysm.accessor.VertexBufferAccessor;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.annotation.Aligned;
import com.elfmcys.ysm.buffer.annotation.Borrowed;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.geckolib3.geo.GeoVertexFormat;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import java.lang.ref.Reference;

public class NativeRenderer {
    // TODO：是否该废弃 light overlay 管线？
    public static void render(BufferBuilder buffer, NativeModelState modelState,
                              int vertexCount, int packedLight, int packedOverlay,
                              int argbColor, RenderContextType contextType) {
        if (vertexCount < 0) {
            throw new IllegalArgumentException("Negative vertex count");
        }
        if (vertexCount == 0) {
            return;
        }

        var vertexBuffer = probeVertexConsumer(buffer, vertexCount);
        var fallback = vertexBuffer.region == null;
        Object bufferObject = fallback
                ? FallbackVertexWriter.getVertexData(vertexCount)
                : vertexBuffer.region.nio();
        int bufferFlags = fallback ? 0 : vertexBuffer.region.size();

        var matBuffer = getMatBuffer();
        var lightAndOverlay = packLightAndOverlay(packedLight, packedOverlay);
        var flags = packFlags(vertexBuffer.type, contextType);

        final boolean rendered;
        try {
            rendered = nRender(bufferObject, bufferFlags, matBuffer.ptr(),
                    modelState.get(), lightAndOverlay, packColor(argbColor), flags, 0);
        } finally {
            Reference.reachabilityFence(buffer);
            Reference.reachabilityFence(bufferObject);
            Reference.reachabilityFence(vertexBuffer.region);
            Reference.reachabilityFence(matBuffer);
            Reference.reachabilityFence(modelState);
        }
        if (!rendered) {
            return;
        }

        if (fallback) {
            FallbackVertexWriter.write(buffer, vertexCount, packedLight,
                    packedOverlay);
        } else {
            var accessor = vertexBuffer.accessor;
            if (accessor == null) {
                throw new IllegalStateException("Missing direct buffer accessor");
            }
            accessor.ysm$advance(vertexCount);
        }
    }

    private static VertexBuffer probeVertexConsumer(BufferBuilder buffer,
                                                    int vertexCount) {
        Object bufferObject = buffer;
        if (bufferObject instanceof VertexBufferAccessor accessor
                && accessor.ysm$ok()
                && !ClientConfig.USE_COMPATIBILITY_RENDERER
                && buffer.getVertexFormat() == GeoVertexFormat.NEW_ENTITY) {
            return new VertexBuffer(accessor, VertexFormatType.VANILLA,
                    accessor.ysm$reserve(vertexCount));
        }
        return new VertexBuffer(null, VertexFormatType.FALLBACK, null);
    }

    @Borrowed
    @Aligned(64)
    public static NativeBuffer getMatBuffer() {
        var buffer = MatBufferHolder.BUFFER;
        var matrices = buffer.nio().order(ByteOrder.nativeOrder()).asFloatBuffer();
        for (var index = 0; index < matrices.capacity(); index++) {
            matrices.put(index, 0.0F);
        }

        matrices.put(0, 1.0F);
        matrices.put(5, 1.0F);
        matrices.put(10, 1.0F);
        matrices.put(15, 1.0F);
        copyGlMatrix(GL11.GL_MODELVIEW_MATRIX, matrices, 16);
        copyGlMatrix(GL11.GL_PROJECTION_MATRIX, matrices, 32);
        matrices.put(48, 1.0F);
        matrices.put(52, 1.0F);
        matrices.put(56, 1.0F);
        return buffer;
    }

    private static void copyGlMatrix(int matrixName, FloatBuffer destination,
                                     int destinationOffset) {
        var scratch = MatBufferHolder.SCRATCH.get();
        scratch.clear();
        GlStateManager.getFloat(matrixName, scratch);
        for (var index = 0; index < 16; index++) {
            destination.put(destinationOffset + index, scratch.get(index));
        }
    }

    private static long packFlags(VertexFormatType v, RenderContextType c) {
        return ((long) v.id() << 2) | c.id();
    }

    private static long packLightAndOverlay(int lightUv, int overlayOv) {
        return ((long) lightUv << 32) | overlayOv;
    }

    static int packColor(int argb) {
        return ((argb >>> 16) & 0xFF) |
                (argb & 0xFF00) |
                ((argb & 0xFF) << 16) |
                (argb & 0xFF000000);
    }

    private static native boolean nRender(Object vertexBuffer, int vertexBufferFlag, long matPtr, long modelStatePtr,
                                          long lightAndOverlay, int color, long flags, long irisEntityId);

    private static final class MatBufferHolder {
        private static final NativeBuffer BUFFER = NativeBuffer.allocate(256, 64);
        private static final ThreadLocal<FloatBuffer> SCRATCH = ThreadLocal.withInitial(
                () -> ByteBuffer.allocateDirect(16 * Float.BYTES)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer());
    }

    private record VertexBuffer(@Nullable VertexBufferAccessor accessor, VertexFormatType type, @Nullable NativeBuffer region){}
}
