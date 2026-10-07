package com.elfmcys.ysm.mixin.client;

import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(VertexFormat.class)
public interface VertexFormatAccessor {
    @Accessor("elements")
    List<VertexFormatElement> ysm$elements();

    @Accessor("uvOffsetsById")
    List<Integer> ysm$uvOffsetsById();
}
