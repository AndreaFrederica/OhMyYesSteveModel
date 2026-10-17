package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.model.resource.client.render.PreparedSceneTextures;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.function.ToIntFunction;

/**
 * FBX renderer with one shared vertex table per mesh and one index buffer per
 * material run. FBX exports commonly split a mesh into many material runs while
 * keeping one corner attribute table; uploading that table once avoids a large
 * per-material duplication on the render thread.
 */
final class FbxMeshRenderer implements AutoCloseable {
    private final GltfRenderResources resources;
    private final ReadLimits limits;
    private final SharedBuffer shared = new SharedBuffer();
    private final GltfMeshRenderer fallback;
    private boolean closed;

    FbxMeshRenderer(GltfRenderResources resources, ReadLimits limits) {
        RenderSystem.assertOnRenderThread();
        this.resources = Objects.requireNonNull(resources);
        this.limits = Objects.requireNonNull(limits);
        this.fallback = new GltfMeshRenderer(resources, limits);
    }

    void render(GeometryFrame frame, List<SceneAsset.Material> materials, GltfSurfaceProgram.View view,
                ToIntFunction<PreparedSceneTextures.Key> textureIds) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("FBX renderer is closed");
        var draws = new ArrayList<Draw>();
        for (var node : frame.draws()) {
            if (!node.visible()) continue;
            var matrix = view.modelView().multiply(node.world());
            matrix.inverse();
            var nodeView = new GltfSurfaceProgram.View(matrix, view.projection(), view.toLight(), view.lightRadiance(),
                    view.diffuseIrradiance(), view.tint(), view.orthographic(), view.lightmap());
            for (var primitive : node.geometry().primitives()) {
                var material = GltfRenderResources.material(materials, primitive.material());
                var program = resources.program(primitive);
                var binding = program.prepare(material, resources.textures(primitive.material()), textureIds, nodeView);
                draws.add(new Draw(primitive, material, program, binding,
                        SceneWinding.clockwise(matrix, view.projection()), depth(primitive, matrix)));
            }
        }
        if (draws.isEmpty()) return;
        if (!sharedCompatible(draws)) {
            fallback.render(frame, materials, view, textureIds);
            return;
        }
        draws.sort(Comparator.comparingInt((Draw d) -> d.material().alphaMode().equals("BLEND") ? 1 : 0)
                .thenComparingDouble(d -> d.material().alphaMode().equals("BLEND") ? d.depth() : 0));
        shared.upload(draws, limits);
        try (var raster = new SceneRasterState()) {
            raster.configure(false, true);
            for (var draw : draws) {
                boolean blend = draw.material().alphaMode().equals("BLEND");
                SceneRasterState.enabled(GL11C.GL_BLEND, blend);
                GL11C.glDepthMask(!blend);
                SceneRasterState.enabled(GL11C.GL_CULL_FACE, !draw.material().doubleSided());
                GL11C.glCullFace(GL11C.GL_BACK);
                GL11C.glFrontFace(draw.clockwise() ? GL11C.GL_CW : GL11C.GL_CCW);
                try (var binding = draw.binding().bind()) {
                    shared.draw(draw);
                }
            }
        }
    }

    private static boolean sharedCompatible(List<Draw> draws) {
        var first = draws.get(0).geometry().attributes();
        int count = first.get("POSITION") == null ? -1 : first.get("POSITION").count();
        if (count < 0) return false;
        for (var draw : draws) {
            var attributes = draw.geometry().attributes();
            if (attributes != first || attributes.get("POSITION") == null || attributes.get("POSITION").count() != count)
                return false;
            for (var entry : first.entrySet()) {
                var candidate = attributes.get(entry.getKey());
                if (candidate == null || candidate.components() != entry.getValue().components() || candidate.count() != count)
                    return false;
            }
        }
        return true;
    }

    private static double depth(MeshAsset.Primitive geometry, Matrix4 transform) {
        if (geometry.indices().size() == 0) return 0;
        var positions = geometry.attributes().get("POSITION").values();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < geometry.indices().size(); i++) {
            int vertex = geometry.indices().get(i) * 3;
            double z = transform.get(0, 2) * (double) positions.get(vertex)
                    + transform.get(1, 2) * (double) positions.get(vertex + 1)
                    + transform.get(2, 2) * (double) positions.get(vertex + 2)
                    + transform.get(3, 2);
            min = Math.min(min, z);
            max = Math.max(max, z);
        }
        return (min + max) * .5;
    }

    private record Draw(MeshAsset.Primitive geometry, SceneAsset.Material material, GltfSurfaceProgram program,
                        GltfSurfaceProgram.PreparedBinding binding, boolean clockwise, double depth) {}

    private static final class SharedBuffer implements AutoCloseable {
        private final int vao = GL30C.glGenVertexArrays();
        private final int vertices = GL15C.glGenBuffers();
        private final List<Integer> elements = new ArrayList<>();
        private final List<Long> indexKeys = new ArrayList<>();
        private final List<Integer> counts = new ArrayList<>();
        private final List<Integer> modes = new ArrayList<>();
        private final IdentityHashMap<Draw, Integer> drawIndices = new IdentityHashMap<>();
        private List<String> fields = List.of();
        private Map<String, Integer> offsets = Map.of();
        private Map<String, Integer> components = Map.of();
        private Object vertexKey;
        private int vertexCount;
        private int stride;
        private Set<Integer> enabled = Set.of();
        private boolean closed;

        void upload(List<Draw> draws, ReadLimits limits) {
            requireOpen();
            var attributes = draws.get(0).geometry().attributes();
            if (vertexKey != attributes) uploadVertices(attributes, limits);
            while (elements.size() < draws.size()) {
                elements.add(GL15C.glGenBuffers());
                indexKeys.add(Long.MIN_VALUE);
                counts.add(0);
                modes.add(GL11C.GL_TRIANGLES);
            }
            drawIndices.clear();
            for (int i = 0; i < draws.size(); i++) {
                drawIndices.put(draws.get(i), i);
                uploadIndices(i, draws.get(i).geometry(), limits);
            }
        }

        private void uploadVertices(Map<String, MeshAsset.Attribute> attributes, ReadLimits limits) {
            fields = new ArrayList<>(attributes.keySet());
            fields.sort(String::compareTo);
            var offsetMap = new LinkedHashMap<String, Integer>();
            var componentMap = new LinkedHashMap<String, Integer>();
            stride = 0;
            vertexCount = attributes.get("POSITION").count();
            for (String field : fields) {
                var value = attributes.get(field);
                offsetMap.put(field, stride);
                componentMap.put(field, value.components());
                stride += value.components();
            }
            long scalars = (long) vertexCount * stride;
            long bytes = scalars * Float.BYTES;
            if (bytes > limits.maxBytes() || scalars > Integer.MAX_VALUE || vertexCount > limits.maxElements())
                throw new IllegalArgumentException("FBX shared vertex buffer budget exceeded");
            FloatBuffer data = MemoryUtil.memAllocFloat((int) scalars);
            try {
                for (int vertex = 0; vertex < vertexCount; vertex++) {
                    for (String field : fields) {
                        var value = attributes.get(field);
                        for (int component = 0; component < value.components(); component++)
                            data.put(value.values().get(vertex * value.components() + component));
                    }
                }
                data.flip();
                int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
                int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
                try {
                    GL30C.glBindVertexArray(vao);
                    GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertices);
                    GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, data, GL15C.GL_STREAM_DRAW);
                } finally {
                    GL30C.glBindVertexArray(previousVao);
                    GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
                }
            } finally {
                MemoryUtil.memFree(data);
            }
            offsets = Map.copyOf(offsetMap);
            components = Map.copyOf(componentMap);
            vertexKey = attributes;
        }

        private void uploadIndices(int index, MeshAsset.Primitive primitive, ReadLimits limits) {
            if (primitive.indices().size() > limits.maxElements()
                    || (long) primitive.indices().size() * Integer.BYTES > limits.maxBytes())
                throw new IllegalArgumentException("FBX shared index buffer budget exceeded");
            long key = 0xcbf29ce484222325L;
            for (int i = 0; i < primitive.indices().size(); i++) key = (key ^ primitive.indices().get(i)) * 0x100000001b3L;
            key ^= ((long) primitive.indices().size() << 32) ^ mode(primitive.topology());
            if (indexKeys.get(index) == key && counts.get(index) == primitive.indices().size()) return;
            IntBuffer data = MemoryUtil.memAllocInt(primitive.indices().size());
            try {
                data.put(primitive.indices().view()).flip();
                int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
                try {
                    GL30C.glBindVertexArray(vao);
                    GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, elements.get(index));
                    GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, data, GL15C.GL_STATIC_DRAW);
                } finally {
                    GL30C.glBindVertexArray(previousVao);
                }
            } finally {
                MemoryUtil.memFree(data);
            }
            indexKeys.set(index, key);
            counts.set(index, primitive.indices().size());
            modes.set(index, mode(primitive.topology()));
        }

        void draw(Draw draw) {
            requireOpen();
            var geometry = draw.geometry();
            int index = drawIndices.getOrDefault(draw, -1);
            if (index < 0) throw new IllegalStateException("FBX shared index buffer was not uploaded");
            int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
            int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
            try {
                GL30C.glBindVertexArray(vao);
                GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertices);
                var locations = new HashSet<Integer>();
                for (var binding : draw.program().layout().entrySet()) {
                    var field = binding.getKey();
                    int location = binding.getValue();
                    var value = geometry.attributes().get(field);
                    if (value == null) throw new IllegalArgumentException("Missing FBX shared attribute: " + field);
                    locations.add(location);
                    GL20C.glVertexAttribPointer(location, value.components(), GL11C.GL_FLOAT, false,
                            stride * Float.BYTES, (long) offsets.get(field) * Float.BYTES);
                    GL20C.glEnableVertexAttribArray(location);
                }
                for (int location : enabled) if (!locations.contains(location)) GL20C.glDisableVertexAttribArray(location);
                enabled = Set.copyOf(locations);
                GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, elements.get(index));
                GL11C.glDrawElements(modes.get(index), counts.get(index), GL11C.GL_UNSIGNED_INT, 0L);
            } finally {
                GL30C.glBindVertexArray(previousVao);
                GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
            }
        }

        private static int mode(MeshAsset.Topology topology) {
            return switch (topology) {
                case POINTS -> GL11C.GL_POINTS;
                case LINES -> GL11C.GL_LINES;
                case LINE_LOOP -> GL11C.GL_LINE_LOOP;
                case LINE_STRIP -> GL11C.GL_LINE_STRIP;
                case TRIANGLES -> GL11C.GL_TRIANGLES;
                case TRIANGLE_STRIP -> GL11C.GL_TRIANGLE_STRIP;
                case TRIANGLE_FAN -> GL11C.GL_TRIANGLE_FAN;
            };
        }

        private void requireOpen() { if (closed) throw new IllegalStateException("FBX shared buffer is closed"); }

        @Override public void close() {
            if (closed) return;
            closed = true;
            for (int buffer : elements) GL15C.glDeleteBuffers(buffer);
            elements.clear();
            GL15C.glDeleteBuffers(vertices);
            GL30C.glDeleteVertexArrays(vao);
        }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        shared.close();
        fallback.close();
    }
}
