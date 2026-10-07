package com.elfmcys.ysm.accessor;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.annotation.Borrowed;
import net.minecraft.client.renderer.vertex.VertexFormat;

public interface VertexBufferAccessor {
    boolean ysm$ok();

    VertexFormat ysm$vertexFormat();

    @Borrowed
    NativeBuffer ysm$reserve(int maxVertexCount);

    void ysm$overlay(int overlayU, int overlayV);

    void ysm$advance(int actualVertexCount);
}
