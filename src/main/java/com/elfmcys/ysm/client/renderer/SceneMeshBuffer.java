package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.MeshAsset;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

/** Instance-owned streaming geometry. Consumes already deformed frames and performs real indexed draws. */
public final class SceneMeshBuffer implements AutoCloseable {
    private final int vao;
    private final int vertices;
    private final int indices;
    private Set<Integer> enabled = Set.of();
    private MeshAsset.Primitive uploaded;
    private Map<String, Integer> layout = Map.of();
    private int count;
    private int mode;
    private boolean closed;

    public SceneMeshBuffer() {
        RenderSystem.assertOnRenderThread();
        int previousError = GL11C.glGetError();
        if (previousError != GL11C.GL_NO_ERROR) throw new IllegalStateException("Host GL error before mesh allocation: " + previousError);
        int newVao = 0, newVertices = 0, newIndices = 0;
        try {
            newVao = GL30C.glGenVertexArrays();
            newVertices = GL15C.glGenBuffers();
            newIndices = GL15C.glGenBuffers();
            if (newVao == 0 || newVertices == 0 || newIndices == 0 || GL11C.glGetError() != GL11C.GL_NO_ERROR) {
                throw new IllegalStateException("Unable to allocate scene mesh buffers");
            }
        } catch (RuntimeException | Error failure) {
            if (newVao != 0) GL30C.glDeleteVertexArrays(newVao);
            if (newVertices != 0) GL15C.glDeleteBuffers(newVertices);
            if (newIndices != 0) GL15C.glDeleteBuffers(newIndices);
            throw failure;
        }
        vao = newVao; vertices = newVertices; indices = newIndices;
    }

    /** Bind only attributes actually used by the selected material shader; source attributes stay immutable. */
    public void upload(MeshAsset.Primitive geometry, Map<String, Integer> attributes, ReadLimits limits) {
        requireOpen();
        if (uploaded == geometry && layout.equals(attributes)) return;
        if (geometry.skinning() != null || !geometry.morphs().isEmpty()) {
            throw new IllegalArgumentException("Scene renderer requires already deformed geometry");
        }
        var bindings = new ArrayList<>(attributes.entrySet());
        bindings.sort(Map.Entry.comparingByValue());
        var locations = new HashSet<Integer>();
        int stride = 0;
        int maxAttributes = GL11C.glGetInteger(GL20C.GL_MAX_VERTEX_ATTRIBS);
        for (var binding : bindings) {
            var attribute = geometry.attributes().get(binding.getKey());
            if (attribute == null || attribute.components() > 4 || binding.getValue() < 0
                    || binding.getValue() >= maxAttributes || !locations.add(binding.getValue())) {
                throw new IllegalArgumentException("Invalid or unavailable scene shader attribute: " + binding);
            }
            stride += attribute.components();
        }
        if (!attributes.containsKey("POSITION")) throw new IllegalArgumentException("Scene shader requires POSITION");
        long vertexScalars = (long) geometry.vertexCount() * stride;
        long bytes = (vertexScalars + geometry.indices().size()) * Integer.BYTES;
        if (bytes > limits.maxBytes() || vertexScalars > Integer.MAX_VALUE
                || geometry.vertexCount() > limits.maxElements() || geometry.indices().size() > limits.maxElements()) {
            throw new IllegalArgumentException("Scene geometry upload budget exceeded");
        }
        int previousError = GL11C.glGetError();
        if (previousError != GL11C.GL_NO_ERROR) throw new IllegalStateException("Host GL error before mesh upload: " + previousError);
        int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        FloatBuffer vertexData = null;
        IntBuffer indexData = null;
        uploaded = null;
        try {
            vertexData = MemoryUtil.memAllocFloat((int) vertexScalars);
            indexData = MemoryUtil.memAllocInt(geometry.indices().size());
            for (int vertex = 0; vertex < geometry.vertexCount(); vertex++) {
                for (var binding : bindings) {
                    var attribute = geometry.attributes().get(binding.getKey());
                    for (int component = 0; component < attribute.components(); component++) {
                        vertexData.put(attribute.values().get(vertex * attribute.components() + component));
                    }
                }
            }
            vertexData.flip(); indexData.put(geometry.indices().view()).flip();
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertices);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, vertexData, GL15C.GL_STREAM_DRAW);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indices);
            GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexData, GL15C.GL_STREAM_DRAW);
            for (int location : enabled) if (!locations.contains(location)) GL20C.glDisableVertexAttribArray(location);
            long offset = 0;
            for (var binding : bindings) {
                int components = geometry.attributes().get(binding.getKey()).components();
                GL20C.glVertexAttribPointer(binding.getValue(), components, GL11C.GL_FLOAT, false, stride * Float.BYTES, offset);
                GL20C.glEnableVertexAttribArray(binding.getValue());
                offset += (long) components * Float.BYTES;
            }
            enabled = Set.copyOf(locations);
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("Scene mesh upload failed: GL " + error);
            count = geometry.indices().size();
            mode = switch (geometry.topology()) {
                case POINTS -> GL11C.GL_POINTS;
                case LINES -> GL11C.GL_LINES;
                case LINE_LOOP -> GL11C.GL_LINE_LOOP;
                case LINE_STRIP -> GL11C.GL_LINE_STRIP;
                case TRIANGLES -> GL11C.GL_TRIANGLES;
                case TRIANGLE_STRIP -> GL11C.GL_TRIANGLE_STRIP;
                case TRIANGLE_FAN -> GL11C.GL_TRIANGLE_FAN;
            };
            layout = Map.copyOf(attributes);
            uploaded = geometry;
        } finally {
            if (vertexData != null) MemoryUtil.memFree(vertexData);
            if (indexData != null) MemoryUtil.memFree(indexData);
            GL30C.glBindVertexArray(previousVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
        }
    }

    /** Caller owns shader, uniforms, material raster state, projection and target framebuffer. */
    public void draw() {
        requireOpen();
        if (uploaded == null) throw new IllegalStateException("Scene mesh has no complete uploaded frame");
        if (count == 0) return;
        int previousError = GL11C.glGetError();
        if (previousError != GL11C.GL_NO_ERROR) throw new IllegalStateException("Host GL error before scene draw: " + previousError);
        int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        boolean restart = GL11C.glIsEnabled(GL31C.GL_PRIMITIVE_RESTART);
        boolean fixedRestart = GL.getCapabilities().OpenGL43 && GL11C.glIsEnabled(GL43C.GL_PRIMITIVE_RESTART_FIXED_INDEX);
        try {
            if (restart) GL11C.glDisable(GL31C.GL_PRIMITIVE_RESTART);
            if (fixedRestart) GL11C.glDisable(GL43C.GL_PRIMITIVE_RESTART_FIXED_INDEX);
            GL30C.glBindVertexArray(vao);
            GL11C.glDrawElements(mode, count, GL11C.GL_UNSIGNED_INT, 0L);
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("Scene mesh draw failed: GL " + error);
        } finally {
            GL30C.glBindVertexArray(previousVao);
            if (restart) GL11C.glEnable(GL31C.GL_PRIMITIVE_RESTART);
            if (fixedRestart) GL11C.glEnable(GL43C.GL_PRIMITIVE_RESTART_FIXED_INDEX);
        }
    }

    private void requireOpen() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Scene mesh buffer is closed");
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true; uploaded = null;
        GL30C.glDeleteVertexArrays(vao);
        GL15C.glDeleteBuffers(vertices);
        GL15C.glDeleteBuffers(indices);
    }
}
