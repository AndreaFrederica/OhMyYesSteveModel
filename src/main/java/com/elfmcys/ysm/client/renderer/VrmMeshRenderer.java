package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.vrm.VrmMaterials;
import com.elfmcys.ysm.model.resource.client.render.PreparedSceneTextures;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import java.util.*;
import java.util.function.ToIntFunction;

/** Instance-owned VRM geometry stream. Playback remains in Lib; this class only uploads and draws a frame. */
public final class VrmMeshRenderer implements AutoCloseable {
    private record Key(int node,int primitive) {}
    private record Draw(Key buffer, MeshAsset.Primitive geometry, VrmMaterials.Material material,
                        VrmSurfaceProgram program, VrmSurfaceProgram.PreparedBinding binding,
                        boolean clockwise, double depth,int gpuBuffer) {}
    private static final class Deformation implements AutoCloseable {
        final SceneDeformation cpu;
        MmdGpuDeformer gpu;
        Deformation(MeshAsset.Primitive mesh,ReadLimits limits){cpu=cc.sirrus.ysmlib.YsmRuntime.scenes().compileDeformation(mesh);
            if(limits!=null&&gpuAvailable())try{gpu=new MmdGpuDeformer(mesh,limits,true);}catch(java.io.IOException|RuntimeException failure){com.elfmcys.ysm.YesSteveModel.LOGGER.warn("VRM compute initialization failed; using CPU deformation",failure);}}
        public void close(){if(gpu!=null){gpu.close();gpu=null;}}
    }
    public static boolean gpuAvailable(){return Boolean.parseBoolean(System.getProperty("ysm.vrm.gpuSkinning","true"))&&MmdGpuDeformer.available();}
    private final VrmRenderResources resources;
    private final ReadLimits limits;
    private final long uploadReservation;
    private final Map<Key,SceneMeshBuffer> buffers = new HashMap<>();
    private final Map<Key,Deformation> deformers=new HashMap<>();
    private final SceneEvaluation evaluator;
    private ScenePose palettePose;
    private final Map<Integer,List<Matrix4>> palettes=new HashMap<>();
    public long gpuDispatches(){return deformers.values().stream().filter(d->d.gpu!=null).mapToLong(d->d.gpu.dispatches()).sum();}
    public long gpuUploadedBytes(){return deformers.values().stream().filter(d->d.gpu!=null).mapToLong(d->d.gpu.uploadedBytes()).sum();}
    public SceneMeshBuffer.UploadStatistics uploadStatistics(){long v=0,i=0,vc=0,ic=0;for(var b:buffers.values()){var s=b.uploadStatistics();v+=s.vertexBytes();i+=s.indexBytes();vc+=s.vertexCapacity();ic+=s.indexCapacity();}return new SceneMeshBuffer.UploadStatistics(v,i,vc,ic);}
    private boolean closed;
    public VrmMeshRenderer(VrmRenderResources resources, ReadLimits limits) {
        this.resources = Objects.requireNonNull(resources); this.limits = Objects.requireNonNull(limits);
        evaluator=cc.sirrus.ysmlib.YsmRuntime.scenes().evaluator(resources.scene());
        // Reserve both native index views, shared vertices and their direct staging before compute admission.
        uploadReservation=resources.uploadReservation();
        if(uploadReservation>limits.maxBytes())throw new IllegalArgumentException("Cumulative VRM retained upload budget exceeded");
    }
    public void render(GeometryFrame frame, VrmMaterials.Frame materials, VrmSurfaceProgram.View view,
                       ToIntFunction<PreparedSceneTextures.Key> textureIds) {
        render(frame,materials,view,textureIds,new SceneModelProfile.Presentation(true,1));
    }
    public void render(GeometryFrame frame,VrmMaterials.Frame materials,VrmSurfaceProgram.View view,
                       ToIntFunction<PreparedSceneTextures.Key> textureIds,SceneModelProfile.Presentation presentation) {
        render(frame,materials,view,textureIds,presentation,null);
    }
    public void render(GeometryFrame frame,VrmMaterials.Frame materials,VrmSurfaceProgram.View view,
                       ToIntFunction<PreparedSceneTextures.Key> textureIds,SceneModelProfile.Presentation presentation,ScenePose pose) {
        RenderSystem.assertOnRenderThread(); if (closed) throw new IllegalStateException("VRM instance renderer is closed");
        if (materials.materials().size() != resources.initialMaterials().materials().size())
            throw new IllegalArgumentException("VRM material frame size mismatch");
        var draws = new ArrayList<Draw>(); long bytes = 0;
        if(palettePose!=pose){palettes.clear();palettePose=pose;}
        // Submit the whole avatar before collecting per-primitive status/bounds to avoid serial GPU stalls.
        for(var node:frame.draws())if(node.visible())for(int ordinal=0;ordinal<node.geometry().primitives().size();ordinal++){
            var input=node.geometry().primitives().get(ordinal);if(input.skinning()==null)continue;
            if(pose==null)throw new IllegalArgumentException("Deferred VRM geometry requires its evaluated pose");
            var key=new Key(node.node(),node.sourcePrimitive(ordinal));var deformer=deformers.computeIfAbsent(key,ignored->new Deformation(input,computeBudget()));
            var palette=palettes.computeIfAbsent(node.node(),n->evaluator.skinPalette(pose,n));var weights=input.morphs().isEmpty()?FloatData.EMPTY:pose.morphWeights().get(node.node());
            if(deformer.gpu!=null&&uploadReservation+computeBytes()-deformer.gpu.retainedBytes()+deformer.gpu.projectedRetainedBytes(palette.size(),weights.size())>limits.maxBytes())deformer.close();
            if(deformer.gpu!=null)try{if(!deformer.gpu.submit(pose,palette,weights))deformer.close();}catch(RuntimeException failure){deformer.close();com.elfmcys.ysm.YesSteveModel.LOGGER.warn("VRM compute submission failed; resolving the same pose on CPU",failure);}
        }
        for (var node : frame.draws()) {
            if (!node.visible()) continue;
            var matrix = view.modelView().multiply(node.world());
            var nodeView = new VrmSurfaceProgram.View(matrix, view.projection(), view.toLight(),
                    view.lightRadiance(), view.ambient(), view.tint(), view.orthographic(),view.lightmap());
            for (int ordinal=0;ordinal<node.geometry().primitives().size();ordinal++) {
                var primitive=node.geometry().primitives().get(ordinal);
                var key=new Key(node.node(),node.sourcePrimitive(ordinal));int gpuBuffer=0;Vec3 center=null;
                if(primitive.skinning()!=null){
                    if(pose==null)throw new IllegalArgumentException("Deferred VRM geometry requires its evaluated pose");
                    var deformer=deformers.get(key);var palette=palettes.computeIfAbsent(node.node(),n->evaluator.skinPalette(pose,n));
                    var weights=primitive.morphs().isEmpty()?FloatData.EMPTY:pose.morphWeights().get(node.node());boolean success=false;
                    if(deformer.gpu!=null){try{success=deformer.gpu.finishUpdate();}catch(RuntimeException failure){com.elfmcys.ysm.YesSteveModel.LOGGER.warn("VRM compute failed; resolving the same pose on CPU",failure);}
                        if(success){gpuBuffer=deformer.gpu.outputBuffer();center=deformer.gpu.boundsCenter();}else deformer.close();}
                    var attributes=success?primitive.attributes():deformer.cpu.deform(palette,weights);
                    primitive=new MeshAsset.Primitive(primitive.topology(),attributes,primitive.indices(),primitive.material(),null,List.of());
                }else if(!primitive.morphs().isEmpty())throw new IllegalArgumentException("Unresolved rigid VRM morphs");
                var program = resources.program(primitive);
                var material = VrmRenderResources.material(materials, primitive.material());
                var binding = program.prepare(material, resources.textures(primitive.material()), textureIds, nodeView);
                int stride = 0; for (var attribute : program.layout().keySet()) stride += primitive.attributes().get(attribute).components();
                bytes += ((long) primitive.vertexCount() * stride + primitive.indices().size()) * 4;
                if (bytes > limits.maxBytes() || draws.size() >= limits.maxElements())
                    throw new IllegalArgumentException("Cumulative VRM GPU geometry budget exceeded");
                draws.add(new Draw(key, primitive, material, program, binding,
                        SceneWinding.clockwise(matrix,view.projection()), material.renderState().alphaMode().equals("BLEND")?(center==null?depth(primitive,matrix):matrix.transformPoint(center).z()):0,gpuBuffer));
            }
        }
        for (var draw : draws) {
            buffers.computeIfAbsent(draw.buffer(),ignored->new SceneMeshBuffer(true)).upload(draw.geometry(), draw.program().layout(), limits);
            if(draw.gpuBuffer()!=0)buffers.get(draw.buffer()).gpuStreams(draw.gpuBuffer());
            checkGl("buffers.upload");
        }
        draws.sort(Comparator.comparingInt((Draw d) -> d.material().renderState().category())
                .thenComparingInt(d -> d.material().renderState().queueOffset())
                .thenComparingDouble(d -> d.material().renderState().alphaMode().equals("BLEND") ? d.depth() : 0));
        try (var raster = new SceneRasterState()) {
            raster.configure(false, true);
            checkGl("raster.configure");
            for (var draw : draws) {
                var state = draw.material().renderState(); boolean blend = state.alphaMode().equals("BLEND");
                configureBlend(state, blend);
                checkGl("configureBlend");
                GL11C.glDepthMask(state.depthWrite());
                int cull = switch (state.cull()) { case NONE -> 0; case FRONT -> GL11C.GL_FRONT; case BACK -> GL11C.GL_BACK; };
                SceneRasterState.enabled(GL11C.GL_CULL_FACE, cull != 0); if (cull != 0) GL11C.glCullFace(cull);
                GL11C.glFrontFace(draw.clockwise() ? GL11C.GL_CW : GL11C.GL_CCW);
                checkGl("cull/frontFace");
                try (var binding = draw.binding().bind()) { buffers.get(draw.buffer()).draw(); }
                // Complete each sorted material before the next transparent surface.
                if (!presentation.outlines()||presentation.outlineScale()==0||state.outlineMode().equals("none") || scalar(draw.material(), "VRMC_materials_mtoon/outlineWidthFactor", "_OutlineWidth", 0) <= 0)
                    continue;
                configureBlend(state, blend);
                GL11C.glDepthMask(state.depthWrite());
                cull = switch (state.outlineCull()) { case NONE -> 0; case FRONT -> GL11C.GL_FRONT; case BACK -> GL11C.GL_BACK; };
                SceneRasterState.enabled(GL11C.GL_CULL_FACE, cull != 0); if (cull != 0) GL11C.glCullFace(cull);
                GL11C.glFrontFace(draw.clockwise() ? GL11C.GL_CW : GL11C.GL_CCW);
                try (var binding = draw.binding().bind(true,presentation.outlineScale())) { buffers.get(draw.buffer()).draw(); }
            }
        }
    }
    private long computeBytes(){long total=0;for(var d:deformers.values())if(d.gpu!=null)total+=d.gpu.retainedBytes();return total;}
    private ReadLimits computeBudget(){long available=limits.maxBytes()-uploadReservation-computeBytes();return available<=0?null:new ReadLimits((int)available,limits.maxElements(),(int)Math.min(available,limits.maxStringBytes()));}
    private static void configureBlend(VrmMaterials.RenderState state, boolean blend) {
        SceneRasterState.enabled(GL11C.GL_BLEND, blend || state.sourceBlend() != 1 || state.destinationBlend() != 0);
        if (blend || state.sourceBlend() != 1 || state.destinationBlend() != 0) {
            // VRM 0/MToon stores UnityEngine.Rendering.BlendMode numbers, while OpenGL
            // uses different enum values for the same factors (SrcAlpha is 5 in Unity,
            // 770 in GL).  Translate at the host boundary instead of passing source data
            // directly to the driver.
            int source = glBlendFactor(state.sourceBlend()), destination = glBlendFactor(state.destinationBlend());
            GL14C.glBlendFuncSeparate(source, destination, source, destination);
        }
        SceneRasterState.enabled(GL13C.GL_SAMPLE_ALPHA_TO_COVERAGE, state.alphaToCoverage());
    }
    private static int glBlendFactor(int unity) {
        return switch (unity) {
            case 0 -> GL11C.GL_ZERO;
            case 1 -> GL11C.GL_ONE;
            case 2 -> GL11C.GL_DST_COLOR;
            case 3 -> GL11C.GL_SRC_COLOR;
            case 4 -> GL11C.GL_ONE_MINUS_DST_COLOR;
            case 5 -> GL11C.GL_SRC_ALPHA;
            case 6 -> GL11C.GL_ONE_MINUS_SRC_COLOR;
            case 7 -> GL11C.GL_DST_ALPHA;
            case 8 -> GL11C.GL_ONE_MINUS_DST_ALPHA;
            case 9 -> GL11C.GL_SRC_ALPHA_SATURATE;
            case 10 -> GL11C.GL_ONE_MINUS_SRC_ALPHA;
            default -> throw new IllegalArgumentException("Unsupported Unity blend factor: " + unity);
        };
    }
    private static float scalar(VrmMaterials.Material material, String modern, String legacy, float fallback) {
        var value = material.parameters().get(material.shader() == VrmMaterials.Shader.MTOON_0
                || material.parameterSpace() == VrmMaterials.ParameterSpace.LEGACY_SHADER ? legacy : modern);
        return value == null || value.size() != 1 ? fallback : value.get(0);
    }
    private static double depth(MeshAsset.Primitive geometry, Matrix4 transform) {
        if (geometry.indices().size() == 0) return 0; double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < geometry.indices().size(); i++) {
            int vertex = geometry.indices().get(i) * 3;
            double z = transform.get(0, 2) * (double) geometry.attributes().get("POSITION").values().get(vertex)
                    + transform.get(1, 2) * (double) geometry.attributes().get("POSITION").values().get(vertex + 1)
                    + transform.get(2, 2) * (double) geometry.attributes().get("POSITION").values().get(vertex + 2) + transform.get(3, 2);
            min = Math.min(min, z); max = Math.max(max, z);
        }
        return (min + max) * .5;
    }
    private static void checkGl(String stage) {
        int error = GL11C.glGetError();
        if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("VRM render failed at " + stage + ": GL " + error);
    }
    @Override public void close() { RenderSystem.assertOnRenderThread(); if (closed) return; closed = true; for (var buffer : buffers.values()) buffer.close(); buffers.clear();for(var d:deformers.values())d.close();deformers.clear();palettes.clear();palettePose=null; }
}
