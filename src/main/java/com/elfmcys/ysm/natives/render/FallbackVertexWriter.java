package com.elfmcys.ysm.natives.render;

import com.elfmcys.ysm.accessor.VertexBufferAccessor;
import com.elfmcys.ysm.natives.NativeProfiler;
import net.minecraft.client.renderer.BufferBuilder;

class FallbackVertexWriter {
    private static final int STRIDE = 8;

    private static final int INDEX_COLOR = 0;
    private static final int INDEX_NORMAL = 1;
    private static final int INDEX_X = 2;
    private static final int INDEX_Y = 3;
    private static final int INDEX_Z = 4;
    private static final int INDEX_TEX_U = 5;
    private static final int INDEX_TEX_V = 6;

    private static int[] VERTEX_DATA;

    static int[] getVertexData(int vertexCount) {
        var size = vertexCount * STRIDE;
        if (VERTEX_DATA == null || VERTEX_DATA.length < size) {
            VERTEX_DATA = new int[size];
        }
        return VERTEX_DATA;
    }

    static void write(BufferBuilder vertexBuffer, int vertexCount,
                      int packedLight, int packedOverlay) {
        Object bufferObject = vertexBuffer;
        if (!(bufferObject instanceof VertexBufferAccessor accessor)) {
            throw new IllegalStateException("BufferBuilder accessor is unavailable");
        }

        try (var ignored = NativeProfiler.beginFallbackVertexWrite()) {
            var vertexData = VERTEX_DATA;
            for (int i = 0; i < vertexCount; i++) {
                var offset = i * STRIDE;

                var color = vertexData[offset + INDEX_COLOR];
                var r = (color & 0xFF) / 255f;
                var g = ((color >>> 8) & 0xFF) / 255f;
                var b = ((color >>> 16) & 0xFF) / 255f;
                var a = (color >>> 24) / 255f;

                var normal = vertexData[offset + INDEX_NORMAL];
                var normalX = ((byte) ((normal & 0xFF))) / 127f;
                var normalY = ((byte) ((normal >>> 8) & 0xFF)) / 127f;
                var normalZ = ((byte) ((normal >>> 16) & 0xFF)) / 127f;

                vertexBuffer.pos(
                                Float.intBitsToFloat(vertexData[offset + INDEX_X]),
                                Float.intBitsToFloat(vertexData[offset + INDEX_Y]),
                                Float.intBitsToFloat(vertexData[offset + INDEX_Z]))
                        .color(r, g, b, a)
                        .tex(Float.intBitsToFloat(vertexData[offset + INDEX_TEX_U]),
                                Float.intBitsToFloat(vertexData[offset + INDEX_TEX_V]));
                accessor.ysm$overlay(packedOverlay & 0xFFFF,
                        packedOverlay >>> 16);
                vertexBuffer.lightmap(packedLight >>> 16,
                        packedLight & 0xFFFF)
                        .normal(normalX, normalY, normalZ)
                        .endVertex();
            }
        }
    }
}
