package com.elfmcys.ysm.format.schema.model;

import com.elfmcys.ysm.proto.mixel.manifest.Manifest;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTarget;
import com.elfmcys.ysm.model.domain.RenderTargetIds;

public final class ModelManifestLookup {
    private ModelManifestLookup() {
    }

    public static String chooseTexture(ModelFileView view, String targetId, String requested) {
        if (view.schema() == ModelSchema.GENERAL_MESH) {
            view.requireRenderTarget(targetId);
            return RenderTargetIds.GENERAL_MESH_VARIANT;
        }
        return chooseTexture(view.getManifest(), targetId, requested);
    }

    public static String chooseTexture(
            Manifest manifest, String targetId, String requested) {
        var target = target(manifest, targetId);
        if (requested != null && !requested.isBlank()) {
            if (!containsTexture(target, requested)) {
                throw new IllegalArgumentException(
                        "Unknown texture " + requested + " for render target " + targetId);
            }
            return requested;
        }
        var settings = manifest.info().settings();
        if (settings.hasDefaultTexture()
                && containsTexture(target, settings.defaultTextureUnsafe())) {
            return settings.defaultTextureUnsafe();
        }
        for (var entry : target.textures().object2ObjectEntrySet()) {
            return entry.getKey();
        }
        throw new IllegalArgumentException("Render target has no texture: " + targetId);
    }

    public static RenderTarget target(
            Manifest manifest, String targetId) {
        for (var target : manifest.renderTargets()) {
            if (target.targetId().equals(targetId)) {
                return target;
            }
        }
        throw new IllegalArgumentException("Unknown render target: " + targetId);
    }

    private static boolean containsTexture(
            RenderTarget target, String name) {
        for (var entry : target.textures().object2ObjectEntrySet()) {
            if (entry.getKey().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
