package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.mmd.MmdMaterials;
import com.elfmcys.ysm.model.resource.client.render.MmdTextureBindings;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.util.Objects;

/** Target-owned programs shared by draw instances. Texture mappings remain owned by their publication binding. */
public final class MmdRenderResources implements AutoCloseable {
    private final MmdMaterials materials;
    private final MmdTextureBindings textures;
    private final MmdSurfaceProgram surface;
    private final MmdSurfaceProgram additional;
    private boolean closed;

    public MmdRenderResources(MmdMaterials materials, MmdTextureBindings textures) throws IOException {
        RenderSystem.assertOnRenderThread();
        this.materials = Objects.requireNonNull(materials); this.textures = Objects.requireNonNull(textures);
        if (materials.definitions().size() != textures.materials().size()) throw new IllegalArgumentException("MMD texture/material count mismatch");
        MmdSurfaceProgram surface = null, additional = null;
        try {
            surface = new MmdSurfaceProgram(false);
            if (materials.definitions().stream().anyMatch(MmdRenderResources::usesAdditional)) additional = new MmdSurfaceProgram(true);
        } catch (Exception | Error failure) {
            if (surface != null) surface.close(); if (additional != null) additional.close(); throw failure;
        }
        this.surface = surface; this.additional = additional;
    }
    public MmdMaterials materials() { return materials; }
    public MmdTextureBindings textures() { return textures; }
    MmdSurfaceProgram program(MmdMaterials.Definition definition) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("MMD target render resources are closed");
        return usesAdditional(definition) ? additional : surface;
    }
    private static boolean usesAdditional(MmdMaterials.Definition definition) {
        return definition.vertexColor() || definition.sphere() != null && definition.sphereMode() == MmdMaterials.SphereMode.SUB_TEXTURE;
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true; surface.close(); if (additional != null) additional.close();
    }
}
