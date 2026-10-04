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
    private record Draw(int buffer, MeshAsset.Primitive geometry, VrmMaterials.Material material,
                        VrmSurfaceProgram program, VrmSurfaceProgram.PreparedBinding binding,
                        boolean clockwise, double depth) {}
    private final VrmRenderResources resources;
    private final ReadLimits limits;
    private final List<SceneMeshBuffer> buffers = new ArrayList<>();
    private boolean closed;
    public VrmMeshRenderer(VrmRenderResources resources, ReadLimits limits) {
        this.resources = Objects.requireNonNull(resources); this.limits = Objects.requireNonNull(limits);
    }
    public void render(GeometryFrame frame, VrmMaterials.Frame materials, VrmSurfaceProgram.View view,
                       ToIntFunction<PreparedSceneTextures.Key> textureIds) {
        RenderSystem.assertOnRenderThread(); if (closed) throw new IllegalStateException("VRM instance renderer is closed");
        if (materials.materials().size() != resources.initialMaterials().materials().size())
            throw new IllegalArgumentException("VRM material frame size mismatch");
        var draws = new ArrayList<Draw>(); long bytes = 0;
        for (var node : frame.draws()) {
            if (!node.visible()) continue;
            var matrix = view.modelView().multiply(node.world());
            var nodeView = new VrmSurfaceProgram.View(matrix, view.projection(), view.toLight(),
                    view.lightRadiance(), view.ambient(), view.tint(), view.orthographic(),view.lightmap());
            for (var primitive : node.geometry().primitives()) {
                if (primitive.skinning() != null || !primitive.morphs().isEmpty())
                    throw new IllegalArgumentException("VRM draw geometry has not been deformed");
                var program = resources.program(primitive);
                var material = VrmRenderResources.material(materials, primitive.material());
                var binding = program.prepare(material, resources.textures(primitive.material()), textureIds, nodeView);
                int stride = 0; for (var attribute : program.layout().keySet()) stride += primitive.attributes().get(attribute).components();
                bytes += ((long) primitive.vertexCount() * stride + primitive.indices().size()) * 4;
                if (bytes > limits.maxBytes() || draws.size() >= limits.maxElements())
                    throw new IllegalArgumentException("Cumulative VRM GPU geometry budget exceeded");
                draws.add(new Draw(draws.size(), primitive, material, program, binding,
                        SceneWinding.clockwise(matrix,view.projection()), depth(primitive, matrix)));
            }
        }
        while (buffers.size() > draws.size()) buffers.remove(buffers.size() - 1).close();
        while (buffers.size() < draws.size()) buffers.add(new SceneMeshBuffer());
        for (var draw : draws) {
            buffers.get(draw.buffer()).upload(draw.geometry(), draw.program().layout(), limits);
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
            }
            for (var draw : draws) {
                var state = draw.material().renderState();
                if (state.outlineMode().equals("none") || scalar(draw.material(), "VRMC_materials_mtoon/outlineWidthFactor", "_OutlineWidth", 0) <= 0)
                    continue;
                SceneRasterState.enabled(GL11C.GL_BLEND, true);
                GL14C.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
                GL11C.glDepthMask(false);
                int cull = switch (state.outlineCull()) { case NONE -> 0; case FRONT -> GL11C.GL_FRONT; case BACK -> GL11C.GL_BACK; };
                SceneRasterState.enabled(GL11C.GL_CULL_FACE, cull != 0); if (cull != 0) GL11C.glCullFace(cull);
                GL11C.glFrontFace(draw.clockwise() ? GL11C.GL_CW : GL11C.GL_CCW);
                try (var binding = draw.binding().bind(true)) { buffers.get(draw.buffer()).draw(); }
            }
        }
    }
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
    @Override public void close() { RenderSystem.assertOnRenderThread(); if (closed) return; closed = true; for (var buffer : buffers) buffer.close(); buffers.clear(); }
}
