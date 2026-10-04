package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.client.renderer.SceneMeshBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

/** Explicit hardware verification of the actual production uploader, independent of a Minecraft world. */
public final class SceneTextureGpuVerification {
    public static void main(String[] args) throws Exception {
        var errors = GLFWErrorCallback.createPrint(System.err).set();
        long window = 0;
        try {
            if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization failed");
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(32, 32, "YSM texture verification", 0, 0);
            if (window == 0) throw new IllegalStateException("No GL context available");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            System.out.println("GL renderer: " + GL11C.glGetString(GL11C.GL_RENDERER));
            System.out.println("GL version: " + GL11C.glGetString(GL11C.GL_VERSION));
            if(java.util.Arrays.asList(args).contains("materials-only")) {
                MmdMaterialGpuVerification.verify();
                GltfMaterialGpuVerification.verify();
                GltfRendererGpuVerification.verify();
                VrmMaterialGpuVerification.verify();
                System.out.println("MMD/glTF/VRM material and raster regressions passed");return;
            }
            if(java.util.Arrays.asList(args).contains("mmd-only")) {
                MmdMaterialGpuVerification.verify();
                System.out.println("MMD GL3.2 material/uploader regression passed");return;
            }
            verify();
            verifyTriangles();
            MmdMaterialGpuVerification.verify();
            GltfMaterialGpuVerification.verify();
            GltfRendererGpuVerification.verify();
            GeneralMeshOwnerGpuVerification.verify();
            VrmMaterialGpuVerification.verify();
            SceneDisplayGpuVerification.verify();
            System.out.println("Production scene texture upload: RGBA32F precision, linear mipmap, sampler, unpack/PBO/binding restoration and release passed");
            System.out.println("Production scene mesh buffer: indexed TRIANGLES/STRIP, arbitrary UV semantic, frame replacement and VAO/restart restoration passed");
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            GLFW.glfwSetErrorCallback(null);
            errors.free();
        }
    }
    private static void verifyTriangles() throws Exception {
        int vertexShader = shader(GL20C.GL_VERTEX_SHADER, """
                #version 150
                in vec3 Position;
                in vec2 Uv;
                out vec2 texcoord;
                void main() { gl_Position=vec4(Position,1); texcoord=Uv; }
                """);
        int fragmentShader = shader(GL20C.GL_FRAGMENT_SHADER, """
                #version 150
                in vec2 texcoord;
                out vec4 Color;
                void main() { Color=vec4(texcoord,0.25,1); }
                """);
        int program = GL20C.glCreateProgram();
        int framebuffer = GL30C.glGenFramebuffers();
        int color = GL11C.glGenTextures();
        int parentVao = GL30C.glGenVertexArrays();
        int parentBuffer = GL15C.glGenBuffers();
        var sample = MemoryUtil.memAllocFloat(4);
        try (var mesh = new SceneMeshBuffer()) {
            GL20C.glAttachShader(program, vertexShader); GL20C.glAttachShader(program, fragmentShader);
            GL20C.glBindAttribLocation(program, 0, "Position"); GL20C.glBindAttribLocation(program, 5, "Uv");
            GL20C.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) throw new AssertionError(GL20C.glGetProgramInfoLog(program));
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, color);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA32F, 32, 32, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, 0L);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, color, 0);
            equal(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER), "verification framebuffer");
            GL11C.glViewport(0, 0, 32, 32); GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glEnable(GL11C.GL_CULL_FACE); GL11C.glFrontFace(GL11C.GL_CCW);
            GL11C.glClearColor(.01f, .02f, .03f, 0); GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            GL20C.glUseProgram(program);
            GL30C.glBindVertexArray(parentVao); GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, parentBuffer);
            var layout = java.util.Map.of("POSITION", 0, "TEXCOORD_4", 5);
            mesh.upload(geometry(MeshAsset.Topology.TRIANGLES, false), layout, ReadLimits.DEFAULT);
            equal(parentVao, GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING), "upload VAO restore");
            equal(parentBuffer, GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING), "upload buffer restore");
            GL31C.glPrimitiveRestartIndex(0); GL11C.glEnable(GL31C.GL_PRIMITIVE_RESTART);
            mesh.draw();
            equal(parentVao, GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING), "draw VAO restore");
            if (!GL11C.glIsEnabled(GL31C.GL_PRIMITIVE_RESTART)) throw new AssertionError("Primitive restart state lost");
            GL11C.glReadPixels(8, 8, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, sample);
            near(8.5f / 32, sample.get(0), "triangle U"); near(8.5f / 32, sample.get(1), "triangle V"); near(1, sample.get(3), "triangle coverage");
            GL11C.glReadPixels(24, 24, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, sample);
            near(0, sample.get(3), "triangle exterior");
            mesh.upload(geometry(MeshAsset.Topology.TRIANGLE_STRIP, false), layout, ReadLimits.DEFAULT);
            mesh.draw();
            GL11C.glReadPixels(24, 24, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, sample);
            near(24.5f / 32, sample.get(0), "strip U"); near(1, sample.get(3), "strip alternate winding");
            mesh.upload(geometry(MeshAsset.Topology.TRIANGLE_STRIP, true), layout, ReadLimits.DEFAULT);
            mesh.draw();
            GL11C.glReadPixels(24, 24, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, sample);
            near(1 - 24.5f / 32, sample.get(0), "new frame UV upload");
            equal(GL11C.GL_NO_ERROR, GL11C.glGetError(), "mesh GL error");
        } finally {
            MemoryUtil.memFree(sample);
            GL11C.glDisable(GL31C.GL_PRIMITIVE_RESTART);
            GL30C.glBindVertexArray(0); GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
            GL20C.glUseProgram(0); GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            GL20C.glDeleteProgram(program); GL20C.glDeleteShader(vertexShader); GL20C.glDeleteShader(fragmentShader);
            GL30C.glDeleteFramebuffers(framebuffer); GL11C.glDeleteTextures(color);
            GL30C.glDeleteVertexArrays(parentVao); GL15C.glDeleteBuffers(parentBuffer);
        }
    }
    private static MeshAsset.Primitive geometry(MeshAsset.Topology topology, boolean flipU) {
        var position = new MeshAsset.Attribute(3, new FloatData(-1,-1,0, 1,-1,0, -1,1,0, 1,1,0));
        var uv = new MeshAsset.Attribute(2, flipU ? new FloatData(1,0, 0,0, 1,1, 0,1) : new FloatData(0,0, 1,0, 0,1, 1,1));
        return new MeshAsset.Primitive(topology, java.util.Map.of("POSITION", position, "TEXCOORD_4", uv),
                topology == MeshAsset.Topology.TRIANGLES ? new IntData(0,1,2) : new IntData(0,1,2,3),
                -1, null, java.util.List.of());
    }
    private static int shader(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source); GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) {
            String log = GL20C.glGetShaderInfoLog(shader); GL20C.glDeleteShader(shader); throw new AssertionError(log);
        }
        return shader;
    }

    private static void verify() throws Exception {
        // Use a real production texture object; reflection only crosses its private ownership boundary.
        var type = Class.forName(MinecraftSceneTextureHost.class.getName() + "$Texture");
        var constructor = type.getDeclaredConstructor(PreparedSceneTextures.Pixels.class);
        constructor.setAccessible(true);
        var pixels = new PreparedSceneTextures.Pixels(2, 2,
                new FloatData(0, 1f / 65535, 2, .25f, 1, 32768f / 65535, 0, .75f,
                        0, 65534f / 65535, 2, .25f, 1, 1, 0, .75f),
                new PreparedSceneTextures.Sampler(9729, 9987, 33648, 33071));
        var texture = (AbstractTexture) constructor.newInstance(pixels);
        int oldTexture = GL11C.glGenTextures();
        int oldBuffer = GL15C.glGenBuffers();
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, oldTexture);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, oldBuffer);
            GL15C.glBufferData(GL21C.GL_PIXEL_UNPACK_BUFFER, 1024, GL15C.GL_STATIC_DRAW);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 8);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 7);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 1);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 2);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SWAP_BYTES, 1);
            texture.load(null);
            equal(oldTexture, GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D), "previous texture");
            equal(oldBuffer, GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING), "previous unpack buffer");
            equal(8, GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT), "unpack alignment");
            equal(7, GL11C.glGetInteger(GL11C.GL_UNPACK_ROW_LENGTH), "unpack row length");
            equal(1, GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_ROWS), "unpack row skip");
            equal(2, GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_PIXELS), "unpack pixel skip");
            equal(1, GL11C.glGetInteger(GL11C.GL_UNPACK_SWAP_BYTES), "unpack byte swap");
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture.getId());
            equal(GL30C.GL_RGBA32F, GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT), "float precision");
            equal(33648, GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S), "S sampler");
            equal(33071, GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T), "T sampler");
            var read = MemoryUtil.memAllocFloat(16);
            try {
                GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, read);
                for (int i = 0; i < 16; i++) near(pixels.rgba().get(i), read.get(i), "base sample " + i);
                GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, read);
                near(.5f, read.get(0), "linear mipmap black/white");
                near(1, read.get(2), "unclamped mipmap color");
                near(.5f, read.get(3), "linear alpha mipmap");
            } finally { MemoryUtil.memFree(read); }
            int id = texture.getId();
            texture.close(); texture.releaseId();
            if (GL11C.glIsTexture(id)) throw new AssertionError("Host texture ID survived release");
            equal(GL11C.GL_NO_ERROR, GL11C.glGetError(), "GL error");
        } finally {
            texture.close(); texture.releaseId();
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL15C.glDeleteBuffers(oldBuffer);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GL11C.glDeleteTextures(oldTexture);
        }
    }
    private static void equal(int expected, int actual, String label) {
        if (expected != actual) throw new AssertionError(label + ": " + actual + " != " + expected);
    }
    private static void near(float expected, float actual, String label) {
        if (!Float.isFinite(actual) || Math.abs(expected - actual) > 1e-7f) throw new AssertionError(label + ": " + actual + " != " + expected);
    }
}
