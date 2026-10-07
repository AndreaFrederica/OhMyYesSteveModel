package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.accessor.VertexBufferAccessor;
import com.elfmcys.ysm.buffer.NativeBuffer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.nio.ByteBuffer;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin implements VertexBufferAccessor {
    @Shadow
    private ByteBuffer byteBuffer;
    @Shadow
    private int vertexCount;
    @Shadow
    private VertexFormat vertexFormat;

    @Shadow
    protected abstract void growBuffer(int increaseAmount);

    @Shadow
    protected abstract void nextVertexFormatIndex();

    @Unique
    @Override
    public boolean ysm$ok() {
        return true;
    }

    @Unique
    @Override
    public VertexFormat ysm$vertexFormat() {
        return vertexFormat;
    }

    @Unique
    @Override
    public NativeBuffer ysm$reserve(int vertexCount) {
        int stride = this.vertexFormat.getSize();
        int size = vertexCount * stride;
        growBuffer(size);
        return NativeBuffer.borrow(this.byteBuffer.slice(this.vertexCount * stride, size));
    }

    @Unique
    @Override
    public void ysm$overlay(int overlayU, int overlayV) {
        int offset = this.vertexCount * this.vertexFormat.getSize()
                + this.vertexFormat.getUvOffsetById(2);
        this.byteBuffer.putShort(offset, (short) overlayU);
        this.byteBuffer.putShort(offset + 2, (short) overlayV);
        this.nextVertexFormatIndex();
    }

    @Override
    public void ysm$advance(int vertexCount) {
        this.vertexCount += vertexCount;
    }
}
