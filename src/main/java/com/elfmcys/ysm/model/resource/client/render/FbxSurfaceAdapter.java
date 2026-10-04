package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.fbx.FbxAssets;
import cc.sirrus.ysmlib.scene.fbx.FbxDetails;
import cc.sirrus.ysmlib.scene.fbx.FbxDocument;
import java.util.*;

/**
 * Maps the portable subset of an FBX material into the host's core PBR surface.
 * The source FBX maps remain in the Lib document; this adapter only chooses the
 * explicit base-color/normal/metallic/roughness/emissive inputs that the generic
 * shader can represent. Unsupported FBX shader properties stay in diagnostics.
 */
public final class FbxSurfaceAdapter {
    private static final FloatData IDENTITY_UV = new FloatData(
            1, 0, 0,
            0, 1, 0,
            0, 0, 1);

    private FbxSurfaceAdapter() {
    }

    public static SceneAsset scene(FbxDocument source, FbxAssets assets) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(assets);
        int textureCount = source.textures().size();
        var textureIndices = new LinkedHashMap<Integer, Integer>();
        for (int i = 0; i < textureCount; i++) textureIndices.put(source.textures().get(i).element(), i);
        // FbxDetails.MaterialTexture stores ufbx's typed_id, while the public
        // texture record stores element_id. Keep both domains addressable.
        for (int i = 0; i < textureCount; i++) {
            int element = source.textures().get(i).element();
            // Do not capture the loop index in a lambda here. Besides keeping
            // this compatible with the Java 21 build, the explicit loop makes
            // the two FBX id domains visible when debugging real assets.
            for (var value : source.elements()) {
                if (value.id() == element) {
                    textureIndices.put(value.typedId(), i);
                    break;
                }
            }
        }
        var uvSets = new LinkedHashMap<String, Integer>();
        for (var mesh : source.meshes()) {
            for (int i = 0; i < mesh.uvSets().size(); i++) uvSets.putIfAbsent(mesh.uvSets().get(i).name(), i);
        }
        var textures = new ArrayList<SceneAsset.Texture>(textureCount);
        var images = new ArrayList<SceneAsset.Image>(textureCount);
        for (int i = 0; i < textureCount; i++) {
            var texture = source.textures().get(i);
            var image = assets.images().get(i);
            textures.add(new SceneAsset.Texture("fbx-texture-" + i, i,
                    9729, 9987, wrap(texture.wrapU()), wrap(texture.wrapV()),
                    Map.of("element", Integer.toString(texture.element()), "uvSet", texture.uvSet())));
            images.add(new SceneAsset.Image("fbx-image-" + i, "", image == null ? "" : image.reference(),
                    image == null ? ByteData.EMPTY : image.bytes(), Map.of()));
        }
        var materials = source.details().materials().stream()
                .map(material -> material(material, source, assets, textureIndices, uvSets))
                .toList();
        var scene = new SceneAsset.Scene(source.info().creator(), new IntData(new int[0]), Map.of());
        var coordinates = new SceneAsset.Coordinates(true, source.info().sourceUnitMeters(), "Y");
        return new SceneAsset(source.info().creator(), coordinates, List.of(), List.of(scene), 0,
                List.of(), List.of(), materials, textures, images, List.of(), List.of(), List.of(), Map.of(),
                source.compatibility());
    }

    private static SceneAsset.Material material(FbxDetails.Material source, FbxDocument document,
                                                FbxAssets assets, Map<Integer, Integer> textureIndices,
                                                Map<String, Integer> uvSets) {
        var parameters = new LinkedHashMap<String, FloatData>();
        var base = first(source.pbr(), "baseColorFactor", "baseColor", "BaseColor", "DiffuseColor", "Diffuse");
        if (base != null && base.hasValue()) parameters.put("baseColor", floats(base.value(), 4, 1));
        var metallic = first(source.pbr(), "metallicFactor", "metallic", "Metalness");
        if (metallic != null && metallic.hasValue()) parameters.put("metallic", floats(metallic.value(), 1, 0));
        var roughness = first(source.pbr(), "roughnessFactor", "roughness", "Roughness");
        if (roughness != null && roughness.hasValue()) parameters.put("roughness", floats(roughness.value(), 1, .8f));
        var emission = first(source.pbr(), "emissiveFactor", "emissive", "EmissiveColor");
        if (emission != null && emission.hasValue()) parameters.put("emissive", floats(emission.value(), 3, 0));

        var mapped = new LinkedHashMap<String, SceneAsset.TextureBinding>();
        for (var texture : source.textures()) {
            int index = textureIndices.getOrDefault(texture.texture(), -1);
            if (index < 0 || !assets.images().containsKey(index)) continue;
            String semantic = semantic(texture.property(), texture.shaderProperty());
            if (semantic != null && !mapped.containsKey(semantic))
                mapped.put(semantic, binding(index, document.textures().get(index), uvSets));
        }
        // ufbx also exposes typed texture references on the PBR/FBX material maps.
        // Those maps cover normal/roughness/emissive inputs that are not present in
        // the convenience material-texture list on some exporters.
        addMapTextures(mapped, source.pbr(), document, assets, textureIndices, uvSets);
        addMapTextures(mapped, source.fbx(), document, assets, textureIndices, uvSets);
        String alpha = "OPAQUE";
        var opacity = first(source.pbr(), "opacity", "Opacity");
        if (opacity != null && opacity.hasValue() && opacity.value().size() > 0 && opacity.value().get(0) < .999) alpha = "BLEND";
        return new SceneAsset.Material("fbx-material-" + source.element(), "metallic-roughness", parameters,
                mapped, alpha, .5f, false, Map.of("source", "fbx", "shader", source.shadingModel()));
    }

    private static void addMapTextures(Map<String, SceneAsset.TextureBinding> mapped,
                                       Map<String, FbxDetails.MaterialMap> maps,
                                       FbxDocument document, FbxAssets assets,
                                       Map<Integer, Integer> textureIndices, Map<String, Integer> uvSets) {
        for (var entry : maps.entrySet()) {
            int index = textureIndices.getOrDefault(entry.getValue().texture(), -1);
            if (index < 0 || !assets.images().containsKey(index)) continue;
            String semantic = semantic(entry.getKey(), entry.getKey());
            if (semantic != null && !mapped.containsKey(semantic))
                mapped.put(semantic, binding(index, document.textures().get(index), uvSets));
        }
    }

    private static SceneAsset.TextureBinding binding(int index, FbxDocument.Texture texture,
                                                     Map<String, Integer> uvSets) {
        int texCoord = texture.uvSet().isEmpty() ? 0 : uvSets.getOrDefault(texture.uvSet(), 0);
        return new SceneAsset.TextureBinding(index, texCoord, uvTransform(texture.uvTransform()), 1,
                Map.of("sourceElement", Integer.toString(texture.element()), "uvSet", texture.uvSet()));
    }

    /** ufbx stores an affine 3x4 matrix in row-major source order; the shader API is column-major 3x3. */
    private static FloatData uvTransform(DoubleData source) {
        if (source == null || source.size() == 0) return IDENTITY_UV;
        if (source.size() < 8) return IDENTITY_UV;
        float[] m = new float[]{(float) source.get(0), (float) source.get(4), 0,
                (float) source.get(1), (float) source.get(5), 0,
                (float) source.get(3), (float) source.get(7), 1};
        for (float value : m) if (!Float.isFinite(value)) return IDENTITY_UV;
        return new FloatData(m);
    }

    private static String semantic(String property, String shaderProperty) {
        String value = (property + " " + shaderProperty).toLowerCase(Locale.ROOT);
        if (value.contains("normal") || value.contains("bump") || value.contains("tangent")) return "normal";
        if (value.contains("rough")) return "metallicRoughness";
        if (value.contains("metal")) return "metallicRoughness";
        if (value.contains("emission") || value.contains("emissive")) return "emissive";
        if (value.contains("diffuse") || value.contains("albedo") || value.contains("basecolor") || value.endsWith(" color")) return "baseColor";
        return null;
    }

    private static FbxDetails.MaterialMap first(Map<String, FbxDetails.MaterialMap> values, String... names) {
        for (String name : names) {
            var value = values.get(name);
            if (value != null) return value;
        }
        for (var entry : values.entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            for (String name : names) if (lower.equals(name.toLowerCase(Locale.ROOT))) return entry.getValue();
        }
        return null;
    }

    private static FloatData floats(DoubleData values, int components, float fallback) {
        if (values == null || values.size() == 0) return new FloatData(fallback);
        int count = Math.min(values.size(), components);
        float[] output = new float[components];
        for (int i = 0; i < components; i++) output[i] = i < count ? (float) values.get(i) : (i == 3 ? 1 : fallback);
        return new FloatData(output);
    }

    private static int wrap(int value) {
        return switch (value) {
            case 33071, 33648, 10497 -> value;
            default -> 10497;
        };
    }
}
