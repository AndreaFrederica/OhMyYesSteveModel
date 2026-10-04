package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.SceneAsset;
import cc.sirrus.ysmlib.scene.SceneImageUsage;
import cc.sirrus.ysmlib.scene.ScenePackageImages;
import cc.sirrus.ysmlib.scene.vrm.VrmMaterials;
import java.util.*;

/** Immutable VRM texture publication plan.  MToon slots keep their source transfer and channel rules. */
public final class VrmTextureBindings {
    private final Map<String, PreparedSceneTextures.Key> textures;
    private final Map<String, Integer> sourceTextures;

    public VrmTextureBindings(String owner, SceneAsset scene, VrmMaterials.Material material) {
        var result = new LinkedHashMap<String, PreparedSceneTextures.Key>();
        var sources = new LinkedHashMap<String, Integer>();
        for (var entry : material.textures().entrySet()) {
            String semantic = entry.getKey();
            var binding = entry.getValue().binding();
            if (binding.texture() < 0 || binding.texture() >= scene.textures().size()) {
                throw new IllegalArgumentException("VRM material texture is outside the scene: " + semantic);
            }
            var source = scene.textures().get(binding.texture());
            var usage = new SceneImageUsage(transfer(entry.getValue().transfer()),
                    semantic.equals("baseColor") || semantic.equals("_MainTex")
                            ? SceneImageUsage.Alpha.STRAIGHT : SceneImageUsage.Alpha.OPAQUE);
            var sampler = new PreparedSceneTextures.Sampler(
                    source.magFilter() < 0 ? 9729 : source.magFilter(),
                    source.minFilter() < 0 ? 9987 : source.minFilter(),
                    source.wrapS(), source.wrapT());
            result.put(semantic, new PreparedSceneTextures.Key(
                    ScenePackageImages.Key.indexed(owner, source.image()), sampler, usage));
            sources.put(semantic, binding.texture());
        }
        textures = Map.copyOf(result);
        sourceTextures = Map.copyOf(sources);
    }

    public Map<String, PreparedSceneTextures.Key> textures() { return textures; }
    public Collection<PreparedSceneTextures.Key> requests() { return textures.values(); }

    public void validate(VrmMaterials.Material material) {
        if (!material.textures().keySet().equals(sourceTextures.keySet())) {
            throw new IllegalArgumentException("VRM material texture binding set changed");
        }
        material.textures().forEach((semantic, texture) -> {
            if (texture.binding().texture() != sourceTextures.get(semantic)) {
                throw new IllegalArgumentException("VRM texture reference changed without publication: " + semantic);
            }
        });
    }

    private static SceneImageUsage.Transfer transfer(VrmMaterials.Transfer transfer) {
        return switch (transfer) {
            case LINEAR -> SceneImageUsage.Transfer.LINEAR;
            case SRGB -> SceneImageUsage.Transfer.SRGB;
            case SOURCE_DEFINED -> SceneImageUsage.Transfer.SOURCE_NUMERIC;
        };
    }
}
