package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.mmd.MmdMaterials;
import cc.sirrus.ysmlib.scene.mmd.MmdPlayback;
import cc.sirrus.ysmlib.YsmRuntime;
import com.elfmcys.ysm.model.resource.client.render.MmdTextureBindings;
import com.elfmcys.ysm.model.resource.client.render.PreparedSceneTextures;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import java.util.*;
import java.util.function.ToIntFunction;

/** Instance-owned MMD draw resources. Frames/textures are borrowed; this class never advances a player. */
public final class MmdMeshRenderer implements AutoCloseable {
    public record UploadStatistics(long vertexUploads,long vertexBytes,long indexUploads,long indexBytes,long stagingAllocations) {}
    public UploadStatistics uploadStatistics() { return buffer.statistics(); }
    private final MmdMaterials materials;
    private final MmdTextureBindings textures;
    private final ReadLimits limits;
    private final MmdRenderResources resources;
    private final MmdSharedMeshBuffer buffer = new MmdSharedMeshBuffer();
    private List<MeshAsset.Primitive> lastEvaluated;
    private List<MeshAsset.Primitive> lastGeometry;
    private boolean closed;
    private MmdGpuDeformer gpu;
    private boolean gpuReported;
    private DeformationProvider.Session cpuFallback;
    private List<MeshAsset.Primitive> gpuGeometry;
    private MmdPlayback.Frame fallbackFrame;
    private Map<String,MeshAsset.Attribute> fallbackAttributes;
    private MmdPlayback.Frame fallbackGeometryFrame;
    private List<MeshAsset.Primitive> fallbackSource, fallbackEvaluated;
    public boolean enableGpu(MeshAsset.Primitive mesh) {
        if(!Boolean.parseBoolean(System.getProperty("ysm.mmd.gpuSkinning","true"))) {
            com.elfmcys.ysm.YesSteveModel.LOGGER.info("MMD GPU skinning disabled by ysm.mmd.gpuSkinning; using CPU");return false;
        }
        if(!MmdGpuDeformer.available()) {
            com.elfmcys.ysm.YesSteveModel.LOGGER.info("MMD GPU skinning unavailable: requires OpenGL 4.3 and 6 SSBO bindings; using CPU");return false;
        }
        try {gpu=new MmdGpuDeformer(mesh,limits);cpuFallback=YsmRuntime.deformation().compile(mesh);return true;}
        catch(Error fatal){if(gpu!=null){gpu.close();gpu=null;}throw fatal;}
        catch(java.io.IOException|RuntimeException rejected){if(gpu!=null){gpu.close();gpu=null;}System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"MMD compute unavailable; using CPU",rejected);return false;}
    }
    public long gpuDispatches(){return gpu==null?0:gpu.dispatches();}
    public long gpuUploadedBytes(){return gpu==null?0:gpu.uploadedBytes();}
    public Map<String,MeshAsset.Attribute> resolvedAttributes(MmdPlayback.Frame frame) {
        if(frame.deformed())return frame.primitives().get(0);
        if(frame!=fallbackFrame){fallbackAttributes=Map.copyOf(cpuFallback.deform(frame.pose().palette(),frame.pose().morphs().meshWeights(),SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION));fallbackFrame=frame;}
        return fallbackAttributes;
    }

    public MmdMeshRenderer(MmdRenderResources resources, ReadLimits limits) {
        RenderSystem.assertOnRenderThread();
        this.resources = Objects.requireNonNull(resources); this.materials = resources.materials();
        this.textures = resources.textures(); this.limits = Objects.requireNonNull(limits);
    }

    /** Clockwise is the final transformed source-front winding, including coordinate reflection and host mirroring. */
    public void render(List<MeshAsset.Primitive> evaluated, MmdMaterials.Frame frame, MmdSurfaceProgram.View view,
                       ToIntFunction<PreparedSceneTextures.Key> textureIds, boolean clockwise) {
        render(evaluated,frame,view,textureIds,clockwise,new SceneModelProfile.Presentation(true,1));
    }
    public void render(List<MeshAsset.Primitive> evaluated, MmdMaterials.Frame frame, MmdSurfaceProgram.View view,
                       ToIntFunction<PreparedSceneTextures.Key> textureIds, boolean clockwise,SceneModelProfile.Presentation presentation) {
        render(evaluated,frame,view,textureIds,clockwise,presentation,null);
    }
    public void render(List<MeshAsset.Primitive> evaluated,MmdMaterials.Frame frame,MmdSurfaceProgram.View view,
                       ToIntFunction<PreparedSceneTextures.Key> textureIds,boolean clockwise,SceneModelProfile.Presentation presentation,MmdPlayback.Frame pose) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("MMD renderer is closed");
        if (evaluated.size() != materials.definitions().size() || frame.materials().size() != evaluated.size())
            throw new IllegalArgumentException("MMD render frame size mismatch");
        boolean gpuFrame=pose!=null&&!pose.deformed();
        boolean gpuReady=gpuFrame&&gpu!=null&&gpu.update(pose,pose.pose().palette(),pose.pose().morphs().meshWeights());
        if(gpuReady&&!gpuReported) {
            com.elfmcys.ysm.YesSteveModel.LOGGER.info("MMD GPU skinning active: first compute dispatch completed, vertices={}",evaluated.isEmpty()?0:evaluated.get(0).vertexCount());
            gpuReported=true;
        }
        if(gpuFrame&&!gpuReady) {
            if(fallbackGeometryFrame!=pose||fallbackSource!=evaluated){
                var attributes=resolvedAttributes(pose);var resolved=new ArrayList<MeshAsset.Primitive>(evaluated.size());
                for(var p:evaluated)resolved.add(new MeshAsset.Primitive(p.topology(),attributes,p.indices(),p.material(),null,List.of()));
                fallbackSource=evaluated;fallbackGeometryFrame=pose;fallbackEvaluated=List.copyOf(resolved);
            }
            evaluated=fallbackEvaluated;
        }
        var geometry = gpuReady&&gpuGeometry!=null?gpuGeometry:evaluated == lastEvaluated && lastGeometry != null
                ? lastGeometry : project(evaluated);
        if(gpuReady)gpuGeometry=geometry;
        long bytes = 0;
        // Validate the entire frame before issuing a draw; each instance has one cumulative buffer budget.
        for (int index = 0; index < evaluated.size(); index++) {
            var definition = materials.definitions().get(index);
            if (!definition.equals(frame.materials().get(index).definition())) throw new IllegalArgumentException("MMD material definition changed");
            var primitive = geometry.get(index);
            int stride = 0;
            for (String semantic : program(definition).layout().keySet()) {
                var attribute = primitive.attributes().get(semantic);
                if (attribute == null) throw new IllegalArgumentException("Missing MMD drawing attribute: " + semantic);
                stride += attribute.components();
            }
            // All MMD material primitives share one deformed vertex table. The
            // shared buffer accounts for those vertices once; only each
            // material's index list is additional storage.
            if (index == 0) bytes += (long) primitive.vertexCount() * stride * Float.BYTES;
            bytes += (long) primitive.indices().size() * Integer.BYTES;
            if (bytes > limits.maxBytes()) throw new IllegalArgumentException("Cumulative MMD instance GPU buffer budget exceeded");
        }
        if(gpuReady)buffer.uploadGpu(geometry,limits,gpu.outputBuffer());else buffer.upload(geometry, limits);
        try (var raster = new SceneRasterState()) {
            raster.configure(clockwise, false);
            // Source material order and depth writes are significant for the legacy MMD renderer.
            for (int i = 0; i < geometry.size(); i++) {
                var material = frame.materials().get(i); var definition = material.definition();
                if (material.values().diffuse().get(3) <= 0 || definition.points() && material.values().edgeSize() <= 0) continue;
                SceneRasterState.enabled(GL11C.GL_CULL_FACE, !definition.doubleSided()); GL11C.glCullFace(GL11C.GL_BACK);
                try (var binding = program(definition).bind(material, textures.materials().get(i), textureIds, view)) { buffer.draw(i); }
            }
            GL11C.glEnable(GL11C.GL_CULL_FACE); GL11C.glCullFace(GL11C.GL_FRONT);
            for (int i = 0; i < geometry.size(); i++) {
                var material = frame.materials().get(i); var definition = material.definition();
                if (!presentation.outlines() || presentation.outlineScale()==0 || !definition.edge() || definition.points() || definition.lines() || material.values().diffuse().get(3) <= 0
                        || material.values().edgeColor().get(3) <= 0 || material.values().edgeSize() <= 0) continue;
                try (var binding = program(definition).bindEdge(material, view,(float)presentation.outlineScale())) { buffer.draw(i); }
            }
        }
    }
    private List<MeshAsset.Primitive> project(List<MeshAsset.Primitive> evaluated) {
        var projected = new ArrayList<MeshAsset.Primitive>(evaluated.size());
        for (int index = 0; index < evaluated.size(); index++)
            projected.add(materials.geometry(index, evaluated.get(index), limits));
        lastEvaluated = evaluated;
        lastGeometry = List.copyOf(projected);
        return lastGeometry;
    }
    private MmdSurfaceProgram program(MmdMaterials.Definition definition) { return resources.program(definition); }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        buffer.close(); lastEvaluated=null;lastGeometry=null;
        if(gpu!=null)gpu.close();if(cpuFallback!=null)cpuFallback.close();gpuGeometry=null;fallbackFrame=null;fallbackAttributes=null;
    }

}
