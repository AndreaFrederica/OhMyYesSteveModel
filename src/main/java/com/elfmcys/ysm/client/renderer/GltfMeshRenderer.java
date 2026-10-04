package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.model.resource.client.render.PreparedSceneTextures;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import java.util.*;
import java.util.function.ToIntFunction;

/** Instance-owned stream buffers; borrows target programs. Rendering never advances playback or physics. */
public final class GltfMeshRenderer implements AutoCloseable {
    private record Draw(int buffer,MeshAsset.Primitive geometry,SceneAsset.Material material,GltfSurfaceProgram program,
                        GltfSurfaceProgram.PreparedBinding binding,boolean clockwise,double depth) {}
    private final GltfRenderResources resources;
    private final ReadLimits limits;
    private final List<SceneMeshBuffer> buffers=new ArrayList<>();
    private boolean closed;
    public GltfMeshRenderer(GltfRenderResources resources,ReadLimits limits) { this.resources=Objects.requireNonNull(resources);this.limits=Objects.requireNonNull(limits); }
    /** View.modelView maps source scene coordinates into the camera. Output is linear, with sRGB attachment conversion enabled. */
    public void render(GeometryFrame frame,List<SceneAsset.Material> materials,GltfSurfaceProgram.View view,
                       ToIntFunction<PreparedSceneTextures.Key> textureIds) {
        RenderSystem.assertOnRenderThread();if(closed) throw new IllegalStateException("glTF instance renderer is closed");
        if(materials.size()!=resources.materials().size()) throw new IllegalArgumentException("glTF material frame size mismatch");
        var draws=new ArrayList<Draw>();long bytes=0;
        for(var node:frame.draws()) {
            if(!node.visible()) continue;
            var matrix=view.modelView().multiply(node.world());matrix.inverse();
            var nodeView=new GltfSurfaceProgram.View(matrix,view.projection(),view.toLight(),view.lightRadiance(),view.diffuseIrradiance(),view.tint(),view.orthographic());
            for(var primitive:node.geometry().primitives()) {
                if(primitive.skinning()!=null || !primitive.morphs().isEmpty()) throw new IllegalArgumentException("glTF draw geometry has not been deformed");
                var program=resources.program(primitive);int stride=0;
                for(String attribute:program.layout().keySet()) stride+=primitive.attributes().get(attribute).components();
                bytes+=((long)primitive.vertexCount()*stride+primitive.indices().size())*4;
                if(bytes>limits.maxBytes() || draws.size()>=limits.maxElements()) throw new IllegalArgumentException("Cumulative glTF GPU geometry budget exceeded");
                var material=GltfRenderResources.material(materials,primitive.material());
                var binding=program.prepare(material,resources.textures(primitive.material()),textureIds,nodeView);
                draws.add(new Draw(draws.size(),primitive,material,program,binding,SceneWinding.clockwise(matrix,view.projection()),depth(primitive,matrix)));
            }
        }
        while(buffers.size()>draws.size()) buffers.remove(buffers.size()-1).close();
        while(buffers.size()<draws.size()) buffers.add(new SceneMeshBuffer());
        for(var draw:draws) buffers.get(draw.buffer()).upload(draw.geometry(),draw.program().layout(),limits);
        // Opaque/masked surfaces write depth first. Alpha-blended primitives are stable-sorted back to front.
        draws.sort(Comparator.comparingInt((Draw d)->d.material().alphaMode().equals("BLEND")?1:0)
                .thenComparingDouble(d->d.material().alphaMode().equals("BLEND")?d.depth():0));
        try(var raster=new SceneRasterState()) {
            raster.configure(false,true);
            for(var draw:draws) {
                var material=draw.material();boolean blend=material.alphaMode().equals("BLEND");
                SceneRasterState.enabled(GL11C.GL_BLEND,blend);GL11C.glDepthMask(!blend);
                SceneRasterState.enabled(GL11C.GL_CULL_FACE,!material.doubleSided());GL11C.glCullFace(GL11C.GL_BACK);
                GL11C.glFrontFace(draw.clockwise()?GL11C.GL_CW:GL11C.GL_CCW);
                try(var binding=draw.binding().bind()) {
                    buffers.get(draw.buffer()).draw();
                }
            }
        }
    }
    private static double depth(MeshAsset.Primitive geometry,Matrix4 transform) {
        if(geometry.indices().size()==0) return 0;
        var positions=geometry.attributes().get("POSITION").values();double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(int i=0;i<geometry.indices().size();i++) {
            int vertex=geometry.indices().get(i)*3;
            double z=transform.get(0,2)*(double)positions.get(vertex)+transform.get(1,2)*(double)positions.get(vertex+1)+transform.get(2,2)*(double)positions.get(vertex+2)+transform.get(3,2);
            min=Math.min(min,z);max=Math.max(max,z);
        }
        return (min+max)*.5;
    }
    public void close() {
        RenderSystem.assertOnRenderThread();if(closed) return;closed=true;
        for(var buffer:buffers) buffer.close();buffers.clear();
    }
}
