package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.fbx.FbxEvaluation;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.client.renderer.*;
import com.elfmcys.ysm.model.resource.client.GeneralMeshModelResources;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Driver-backed VRM MToon 1 smoke test; verifies the real host shader rather than only Lib material evaluation. */
public final class VrmMaterialGpuVerification {
    public static void main(String[] args) throws Exception {
        var errors = org.lwjgl.glfw.GLFWErrorCallback.createPrint(System.err).set();
        long window = 0;
        try {
            if (!org.lwjgl.glfw.GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization failed");
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE, org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE);
            org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT, org.lwjgl.glfw.GLFW.GLFW_TRUE);
            window = org.lwjgl.glfw.GLFW.glfwCreateWindow(32, 32, "YSM VRM verification", 0, 0);
            if (window == 0) throw new IllegalStateException("No GL context available");
            org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(window);
            org.lwjgl.opengl.GL.createCapabilities();
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            System.out.println("GL renderer: " + org.lwjgl.opengl.GL11C.glGetString(org.lwjgl.opengl.GL11C.GL_RENDERER));
            verify();
        } finally {
            if (window != 0) org.lwjgl.glfw.GLFW.glfwDestroyWindow(window);
            org.lwjgl.glfw.GLFW.glfwTerminate();
            org.lwjgl.glfw.GLFW.glfwSetErrorCallback(null);
            errors.free();
        }
    }
    static void verify() throws Exception {
        var source = new ScenePackage(new ScenePackage.Source("vrm", "materials-avatar.gltf", ScenePackage.Format.VRM),
                new ScenePackage.Settings(1, -1, FbxEvaluation.SkinSpace.BIND_WORLD), List.of(), List.of(),
                Map.of("materials-avatar.gltf", resource("/three-vrm-oracle/materials-avatar.gltf")));
        var target = GeneralMeshModelResources.prepare(source, ReadLimits.DEFAULT, () -> false);
        try (target) {
            target.publish(new SceneTexturePublication.Host<Integer>() {
                public Integer register(PreparedSceneTextures.Pixels pixels) { throw new AssertionError("fixture unexpectedly requested a texture"); }
                public void release(Integer value) { }
            }, value -> value, () -> false);
            checkGl("target.publish");
            var range = new AnimationPreview.Range(0, 1, 30);
            try (var instance = new GeneralMeshInstance(target, ScenePackagePlayback.Selection.REST, range,
                    ScenePackagePlayback.Settings.preview())) {
                var view = new GltfSurfaceProgram.View(Matrix4.IDENTITY, Matrix4.IDENTITY,
                        new Vec3(0, 0, 1), new Vec3(1, 1, 1), new Vec3(.2f, .2f, .2f),
                        new FloatData(1, 1, 1, 1));
                checkGl("before render");
                instance.render(false, view);
                checkGl("after third-person render");
                instance.render(true, view);
                checkGl("after first-person render");
            }
        }
        System.out.println("VRM MToon host: MToon 1 material variants, alpha/cull/outline passes and first/third-person frame rendering passed");
    }

    private static ByteData resource(String path) throws Exception {
        try (var input = VrmMaterialGpuVerification.class.getResourceAsStream(path)) {
            return new ByteData(Objects.requireNonNull(input, path).readAllBytes());
        }
    }

    private static void checkGl(String stage) {
        int error = org.lwjgl.opengl.GL11C.glGetError();
        if (error != org.lwjgl.opengl.GL11C.GL_NO_ERROR) throw new AssertionError("VRM fixture GL error at " + stage + ": " + error);
    }
}
