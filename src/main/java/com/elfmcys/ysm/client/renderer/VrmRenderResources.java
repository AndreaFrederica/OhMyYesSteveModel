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
    private long uploadReservation;
    long uploadReservation(){return uploadReservation;}

    public VrmRenderResources(String owner, SceneAsset scene, GeometryFrame initial,
                              VrmMaterials.Frame initialMaterials) throws IOException {
        RenderSystem.assertOnRenderThread();
        this.scene = Objects.requireNonNull(scene);
        this.initialMaterials = Objects.requireNonNull(initialMaterials);
        try {
            var reservations=new HashMap<String,long[]>();
            for (var draw : initial.draws()) for (int ordinal=0;ordinal<draw.geometry().primitives().size();ordinal++) {
                var primitive=draw.geometry().primitives().get(ordinal);
                var reservation=reservations.computeIfAbsent(draw.node()+":"+draw.sourcePrimitive(ordinal),ignored->new long[2]);
                var key = Variant.of(primitive);
                if (programs.containsKey(key)) {reserve(reservation,primitive,programs.get(key));continue;}
                var material = material(initialMaterials, primitive.material());
                textures.computeIfAbsent(primitive.material(), ignored -> new VrmTextureBindings(owner, scene, material));
                var program=new VrmSurfaceProgram(primitive, material);programs.put(key,program);reserve(reservation,primitive,program);
            }
            for(var bytes:reservations.values())uploadReservation+=2*bytes[0]+3*bytes[1];
        } catch (Exception | Error failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void reserve(long[] reservation,MeshAsset.Primitive primitive,VrmSurfaceProgram program){
        int stride=0;for(String name:program.layout().keySet())stride+=primitive.attributes().get(name).components();
        reservation[0]=Math.max(reservation[0],4L*primitive.vertexCount()*stride);reservation[1]=Math.max(reservation[1],4L*primitive.indices().size());
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
