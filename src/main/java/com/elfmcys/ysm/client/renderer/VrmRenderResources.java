package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.vrm.VrmMaterials;
import com.elfmcys.ysm.model.resource.client.render.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.util.*;

/** Target-owned MToon shader variants and immutable VRM texture bindings. */
public final class VrmRenderResources implements AutoCloseable {
    private record Variant(int material, boolean triangles, Map<String, Integer> shapes) {
        static Variant of(MeshAsset.Primitive primitive) {
            var shapes = new TreeMap<String, Integer>();
            primitive.attributes().forEach((key, value) -> shapes.put(key, value.components()));
            boolean triangles = switch (primitive.topology()) {
                case TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN -> true;
                default -> false;
            };
            return new Variant(primitive.material(), triangles, Map.copyOf(shapes));
        }
    }
    private final SceneAsset scene;
    private final VrmMaterials.Frame initialMaterials;
    private final Map<Integer, VrmTextureBindings> textures = new LinkedHashMap<>();
    private final Map<Variant, VrmSurfaceProgram> programs = new LinkedHashMap<>();
    private boolean closed;

    public VrmRenderResources(String owner, SceneAsset scene, GeometryFrame initial,
                              VrmMaterials.Frame initialMaterials) throws IOException {
        RenderSystem.assertOnRenderThread();
        this.scene = Objects.requireNonNull(scene);
        this.initialMaterials = Objects.requireNonNull(initialMaterials);
        try {
            for (var draw : initial.draws()) for (var primitive : draw.geometry().primitives()) {
                var key = Variant.of(primitive);
                if (programs.containsKey(key)) continue;
                var material = material(initialMaterials, primitive.material());
                textures.computeIfAbsent(primitive.material(), ignored -> new VrmTextureBindings(owner, scene, material));
                programs.put(key, new VrmSurfaceProgram(primitive, material));
            }
        } catch (Exception | Error failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public SceneAsset scene() { return scene; }
    public VrmMaterials.Frame initialMaterials() { return initialMaterials; }
    public Set<PreparedSceneTextures.Key> textureRequests() {
        var keys = new LinkedHashSet<PreparedSceneTextures.Key>();
        textures.values().forEach(binding -> keys.addAll(binding.requests()));
        return Collections.unmodifiableSet(keys);
    }
    VrmSurfaceProgram program(MeshAsset.Primitive geometry) {
        if (closed) throw new IllegalStateException("VRM target programs are closed");
        var result = programs.get(Variant.of(geometry));
        if (result == null) throw new IllegalArgumentException("VRM frame requires an unpublished shader variant");
        return result;
    }
    VrmTextureBindings textures(int material) {
        return Objects.requireNonNull(textures.get(material), "Unpublished VRM material");
    }
    static VrmMaterials.Material material(VrmMaterials.Frame materials, int index) {
        if (index < 0) return defaultMaterial();
        return materials.materials().get(index);
    }
    /** glTF permits a primitive without a material. VRM inherits that default-material rule. */
    static VrmMaterials.Material defaultMaterial() {
        return new VrmMaterials.Material(-1, "gltf-default", VrmMaterials.Shader.GLTF,
                VrmMaterials.ParameterSpace.GLTF_LINEAR,
                Map.of("baseColor", new FloatData(1, 1, 1, 1), "emissive", new FloatData(0, 0, 0)),
                Map.of(), Map.of(), new VrmMaterials.RenderState("OPAQUE", .5f, true,
                        VrmMaterials.Cull.BACK, 1, 0, false, 0, 0, -1, "none", VrmMaterials.Cull.FRONT),
                0, new CompatibilityReport(List.of(new CompatibilityReport.Feature("vrm.default-material",
                        CompatibilityReport.Level.RENDERED, false, "glTF default primitive material")), List.of()));
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread(); if (closed) return; closed = true;
        RuntimeException failure = null;
        for (var program : programs.values()) try { program.close(); }
        catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        programs.clear(); if (failure != null) throw failure;
    }
}
