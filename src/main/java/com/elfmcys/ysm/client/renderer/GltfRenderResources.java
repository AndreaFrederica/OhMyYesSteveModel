package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.model.resource.client.render.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.util.*;

/** Target-owned programs and material bindings. Every variant is compiled before publication. */
public final class GltfRenderResources implements AutoCloseable {
    static final SceneAsset.Material DEFAULT=new SceneAsset.Material("","metallic-roughness",Map.of(),Map.of(),"OPAQUE",.5f,false,Map.of());
    private record Variant(int material,boolean triangles,Map<String,Integer> shapes) {
        static Variant of(MeshAsset.Primitive primitive) {
            var shapes=new TreeMap<String,Integer>();primitive.attributes().forEach((key,value)->shapes.put(key,value.components()));
            boolean triangles=switch(primitive.topology()) { case TRIANGLES,TRIANGLE_STRIP,TRIANGLE_FAN->true;default->false; };
            return new Variant(primitive.material(),triangles,Map.copyOf(shapes));
        }
    }
    private final List<SceneAsset.Material> materials;
    private final Map<Integer,GltfTextureBindings> textures=new LinkedHashMap<>();
    private final Map<Variant,GltfSurfaceProgram> programs=new LinkedHashMap<>();
    private boolean closed;
    public GltfRenderResources(String owner,SceneAsset source,GeometryFrame initial) throws IOException {
        RenderSystem.assertOnRenderThread();materials=source.materials();
        try {
            for(var draw:initial.draws()) for(var primitive:draw.geometry().primitives()) {
                var key=Variant.of(primitive);if(programs.containsKey(key)) continue;
                var material=material(materials,primitive.material());
                textures.computeIfAbsent(primitive.material(),ignored->new GltfTextureBindings(owner,source,material));
                programs.put(key,new GltfSurfaceProgram(primitive,material));
            }
        } catch(Exception|Error failure) { try { close(); } catch(RuntimeException cleanup) { failure.addSuppressed(cleanup); }throw failure; }
    }
    public List<SceneAsset.Material> materials() { return materials; }
    public Set<PreparedSceneTextures.Key> textureRequests() {
        var keys=new LinkedHashSet<PreparedSceneTextures.Key>();textures.values().forEach(t->keys.addAll(t.requests()));return Collections.unmodifiableSet(keys);
    }
    GltfSurfaceProgram program(MeshAsset.Primitive geometry) {
        if(closed) throw new IllegalStateException("glTF target programs are closed");
        var result=programs.get(Variant.of(geometry));
        if(result==null) throw new IllegalArgumentException("glTF frame requires an unpublished shader variant");return result;
    }
    GltfTextureBindings textures(int material) { return Objects.requireNonNull(textures.get(material),"Unpublished glTF material"); }
    static SceneAsset.Material material(List<SceneAsset.Material> materials,int index) { return index==-1?DEFAULT:materials.get(index); }
    public void close() {
        RenderSystem.assertOnRenderThread();if(closed) return;closed=true;
        RuntimeException failure=null;
        for(var program:programs.values()) try { program.close(); } catch(RuntimeException error) { if(failure==null) failure=error;else failure.addSuppressed(error); }
        programs.clear();if(failure!=null) throw failure;
    }
}
