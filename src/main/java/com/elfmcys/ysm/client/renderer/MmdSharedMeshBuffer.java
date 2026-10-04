package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.MeshAsset;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

/**
 * Shared MMD vertex storage. MMD material primitives all reference one
 * deformed vertex table; only their index ranges differ. Keeping one VAO/VBO
 * and one small EBO per material avoids uploading the complete PMX vertex
 * table once for every material.
 */
final class MmdSharedMeshBuffer implements AutoCloseable {
    private final int vao;
    private final int vertices;
    private final List<Integer> elementBuffers = new ArrayList<>();
    private final List<Object> indexKeys = new ArrayList<>();
    private final List<Integer> counts = new ArrayList<>();
    private final List<Integer> modes = new ArrayList<>();
    private Map<String, Integer> layout = Map.of();
    private Set<Integer> enabled = Set.of();
    private Object vertexKey;
    private int vertexCount;
    private boolean closed;
    private final boolean referenceUpload = Boolean.getBoolean("ysm.mmd.referenceUpload");
    private FloatBuffer staging;
    private long gpuVertexCapacity;
    private long vertexUploads,vertexBytes,indexUploads,indexBytes,stagingAllocations;
    private int externalVertices;

    MmdMeshRenderer.UploadStatistics statistics() {
        return new MmdMeshRenderer.UploadStatistics(vertexUploads,vertexBytes,indexUploads,indexBytes,stagingAllocations);
    }

    MmdSharedMeshBuffer() {
        RenderSystem.assertOnRenderThread();
        vao = GL30C.glGenVertexArrays();
        vertices = GL15C.glGenBuffers();
        if (vao == 0 || vertices == 0) throw new IllegalStateException("Unable to allocate shared MMD mesh buffers");
    }

    void upload(List<MeshAsset.Primitive> geometry, ReadLimits limits) {
        if(externalVertices!=0){externalVertices=0;vertexKey=null;}
        upload(geometry,limits,true);
    }
    private void upload(List<MeshAsset.Primitive> geometry,ReadLimits limits,boolean cpuVertices) {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        if (geometry.isEmpty()) return;
        var attributes = geometry.get(0).attributes();
        var desired = new LinkedHashMap<String, Integer>();
        desired.put("POSITION", 0);
        desired.put("NORMAL", 1);
        desired.put("TEXCOORD_0", 2);
        if (attributes.containsKey("_MMD_UV1")) desired.put("_MMD_UV1", 3);
        desired.put("_MMD_EDGE_SCALE", 4);
        if (cpuVertices && (vertexKey != attributes || !layout.equals(desired))) uploadVertices(attributes, desired, limits);

        while (elementBuffers.size() < geometry.size()) {
            elementBuffers.add(GL15C.glGenBuffers());
            indexKeys.add(null);
            counts.add(0);
            modes.add(GL11C.GL_TRIANGLES);
        }
        for (int i = 0; i < geometry.size(); i++) {
            var primitive = geometry.get(i);
            if (indexKeys.get(i) != primitive.indices() || modes.get(i) != mode(primitive.topology())) {
                uploadIndices(i, primitive, limits);
            }
        }
    }
    void uploadGpu(List<MeshAsset.Primitive> geometry,ReadLimits limits,int gpuBuffer) {
        upload(geometry,limits,false);
        if(externalVertices==gpuBuffer)return;
        int previousVao=GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),previousBuffer=GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try {
            GL30C.glBindVertexArray(vao);GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER,gpuBuffer);
            int[] components={3,3,2,4,1},offsets={0,3,10,12,16};
            for(int location=0;location<5;location++){GL20C.glVertexAttribPointer(location,components[location],GL11C.GL_FLOAT,false,17*4,offsets[location]*4L);GL20C.glEnableVertexAttribArray(location);}
            if(GL11C.glGetError()!=GL11C.GL_NO_ERROR)throw new IllegalStateException("GPU MMD vertex binding failed");
            enabled=Set.of(0,1,2,3,4);externalVertices=gpuBuffer;vertexKey=null;
        }finally{GL30C.glBindVertexArray(previousVao);GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER,previousBuffer);}
    }

    void draw(int index) {
        RenderSystem.assertOnRenderThread();
        requireOpen();
        if (index < 0 || index >= elementBuffers.size()) throw new IndexOutOfBoundsException(index);
        if (counts.get(index) == 0) return;
        int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        try {
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, elementBuffers.get(index));
            GL11C.glDrawElements(modes.get(index), counts.get(index), GL11C.GL_UNSIGNED_INT, 0L);
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("Shared MMD draw failed: GL " + error);
        } finally {
            GL30C.glBindVertexArray(previousVao);
        }
    }

    private void uploadVertices(Map<String, MeshAsset.Attribute> attributes, Map<String, Integer> desired,
                                ReadLimits limits) {
        var bindings = new ArrayList<>(desired.entrySet());
        bindings.sort(Map.Entry.comparingByValue());
        int stride = 0;
        for (var binding : bindings) {
            var attribute = attributes.get(binding.getKey());
            if (attribute == null || attribute.components() > 4)
                throw new IllegalArgumentException("Missing shared MMD drawing attribute: " + binding.getKey());
            stride += attribute.components();
        }
        vertexCount = attributes.get("POSITION").count();
        long scalars = (long) vertexCount * stride;
        long bytes = scalars * Float.BYTES;
        if (bytes > limits.maxBytes() || scalars > Integer.MAX_VALUE || vertexCount > limits.maxElements())
            throw new IllegalArgumentException("Shared MMD vertex buffer budget exceeded");
        FloatBuffer data;
        if(referenceUpload) { data=MemoryUtil.memAllocFloat((int)scalars);stagingAllocations++; }
        else {
            if(staging==null || staging.capacity()<scalars) {
                var replacement=MemoryUtil.memAllocFloat((int)scalars);
                if(staging!=null)MemoryUtil.memFree(staging);
                staging=replacement;stagingAllocations++;
            }
            data=staging;data.clear();data.limit((int)scalars);
        }
        int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try {
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                for (var binding : bindings) {
                    var attribute = attributes.get(binding.getKey());
                    for (int component = 0; component < attribute.components(); component++)
                        data.put(attribute.values().get(vertex * attribute.components() + component));
                }
            }
            data.flip();
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertices);
            if(referenceUpload) GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER,data,GL15C.GL_STREAM_DRAW);
            else {
                if(gpuVertexCapacity<bytes) { GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER,bytes,GL15C.GL_STREAM_DRAW);gpuVertexCapacity=bytes; }
                GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER,0L,data);
            }
            int maxAttributes = GL11C.glGetInteger(GL20C.GL_MAX_VERTEX_ATTRIBS);
            for (int location : enabled) if (!desired.containsValue(location)) GL20C.glDisableVertexAttribArray(location);
            long offset = 0;
            for (var binding : bindings) {
                int components = attributes.get(binding.getKey()).components();
                int location = binding.getValue();
                if (location < 0 || location >= maxAttributes) throw new IllegalArgumentException("Unavailable MMD vertex attribute: " + location);
                GL20C.glVertexAttribPointer(location, components, GL11C.GL_FLOAT, false, stride * Float.BYTES, offset);
                GL20C.glEnableVertexAttribArray(location);
                offset += (long) components * Float.BYTES;
            }
            enabled = Set.copyOf(desired.values());
            layout = Map.copyOf(desired);
            int error=GL11C.glGetError();
            if(error!=GL11C.GL_NO_ERROR) { vertexKey=null;gpuVertexCapacity=0;throw new IllegalStateException("MMD vertex upload failed: GL "+error); }
            vertexKey = attributes;
            vertexUploads++;vertexBytes+=bytes;
        } finally {
            if(referenceUpload)MemoryUtil.memFree(data);
            GL30C.glBindVertexArray(previousVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
        }
    }

    private void uploadIndices(int index, MeshAsset.Primitive primitive, ReadLimits limits) {
        if (primitive.indices().size() > limits.maxElements() || (long) primitive.indices().size() * Integer.BYTES > limits.maxBytes())
            throw new IllegalArgumentException("MMD index buffer budget exceeded");
        IntBuffer data = MemoryUtil.memAllocInt(primitive.indices().size());
        try {
            data.put(primitive.indices().view()).flip();
            int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
            try {
                GL30C.glBindVertexArray(vao);
                GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, elementBuffers.get(index));
                GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, data, GL15C.GL_STATIC_DRAW);
                int error=GL11C.glGetError();
                if(error!=GL11C.GL_NO_ERROR) {indexKeys.set(index,null);throw new IllegalStateException("MMD index upload failed: GL "+error);}
                indexKeys.set(index, primitive.indices());
                counts.set(index, primitive.indices().size());
                modes.set(index, mode(primitive.topology()));
                indexUploads++;indexBytes+=(long)primitive.indices().size()*Integer.BYTES;
            } finally {
                GL30C.glBindVertexArray(previousVao);
            }
        } finally {
            MemoryUtil.memFree(data);
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

    private void requireOpen() { if (closed) throw new IllegalStateException("Shared MMD mesh buffer is closed"); }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        if(staging!=null){MemoryUtil.memFree(staging);staging=null;}
        for (int buffer : elementBuffers) GL15C.glDeleteBuffers(buffer);
        elementBuffers.clear();
        GL15C.glDeleteBuffers(vertices);
        GL30C.glDeleteVertexArrays(vao);
    }
}
