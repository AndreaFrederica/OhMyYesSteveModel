package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Core glTF color/data interpretation; every source texture keeps its own sampler and image identity. */
public final class GltfTextureBindings {
    private final Map<String, PreparedSceneTextures.Key> textures;
    private final Map<String, Integer> sourceTextures;
    public GltfTextureBindings(String owner, SceneAsset scene, SceneAsset.Material material) {
        var output = new LinkedHashMap<String, PreparedSceneTextures.Key>();
        for (var entry : material.textures().entrySet()) {
            String semantic = entry.getKey();
            if (!Set.of("baseColor", "metallicRoughness", "normal", "occlusion", "emissive").contains(semantic))
                throw new IllegalArgumentException("glTF material texture requires its extension renderer: " + semantic);
            var source = scene.textures().get(entry.getValue().texture());
            boolean color = semantic.equals("baseColor") || semantic.equals("emissive");
            var usage = new SceneImageUsage(color ? SceneImageUsage.Transfer.SRGB : SceneImageUsage.Transfer.LINEAR,
                    semantic.equals("baseColor") ? SceneImageUsage.Alpha.STRAIGHT : SceneImageUsage.Alpha.OPAQUE);
            // glTF leaves absent min/mag filters implementation-defined. Choose trilinear/linear, with full mip storage.
            var sampler = new PreparedSceneTextures.Sampler(source.magFilter() < 0 ? 9729 : source.magFilter(),
                    source.minFilter() < 0 ? 9987 : source.minFilter(), source.wrapS(), source.wrapT());
            output.put(semantic, new PreparedSceneTextures.Key(ScenePackageImages.Key.indexed(owner, source.image()), sampler, usage));
        }
        textures = Collections.unmodifiableMap(output);
        var indices = new LinkedHashMap<String, Integer>();
        material.textures().forEach((semantic, binding) -> indices.put(semantic, binding.texture()));
        sourceTextures = Map.copyOf(indices);
    }
    public Map<String, PreparedSceneTextures.Key> textures() { return textures; }
    public Collection<PreparedSceneTextures.Key> requests() { return textures.values(); }
    /** Animated factors/UV transforms may change, but an unpublished image reference cannot use the old binding. */
    public void validate(SceneAsset.Material material) {
        if (!material.textures().keySet().equals(sourceTextures.keySet()))
            throw new IllegalArgumentException("glTF texture binding set changed");
        material.textures().forEach((semantic, binding) -> {
            if (binding.texture() != sourceTextures.get(semantic))
                throw new IllegalArgumentException("glTF texture reference changed without publication: " + semantic);
        });
    }
}
