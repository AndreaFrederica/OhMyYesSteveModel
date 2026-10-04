package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.fbx.FbxEvaluation;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.client.renderer.GeneralMeshInstance;
import com.elfmcys.ysm.client.renderer.GltfSurfaceProgram;
import com.elfmcys.ysm.model.resource.client.GeneralMeshModelResources;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-asset smoke for the generic target owner. It uses the same Lib package
 * loader, texture publication and surface programs as the Mod, but keeps the
 * test independent of a Minecraft world. It intentionally checks draw
 * submission and both views; image similarity remains a separate DCC task.
 */
public final class RealMeshGpuVerification {
    private static final ReadLimits LIMITS = new ReadLimits(1_500_000_000, 100_000_000, 4 * 1024 * 1024);
    private static final int WINDOW_WIDTH = 720;
    private static final int WINDOW_HEIGHT = 720;
    private static final int STAGE_SECONDS = Integer.getInteger("ysm.meshGpuStageSeconds", 8);

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0 ? Path.of("D:/Projects/ysm/vrc-mmd") : Path.of(args[0]);
        String mode = System.getProperty("ysm.meshGpuMode", "all").toLowerCase(Locale.ROOT);
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Missing real asset root: " + root);
        Path pmx = inputPath(root, "ysm.meshGpuModel", ".pmx");
        Path vmd = inputPath(root, "ysm.meshGpuMotion", ".vmd");
        Path fbx = inputPath(root, "ysm.meshGpuFbx", ".fbx");
        if ((mode.equals("all") || mode.equals("pmx")) && (pmx == null || vmd == null))
            throw new IllegalArgumentException("PMX mode needs a PMX and VMD; use -Dysm.meshGpuModel=... and -Dysm.meshGpuMotion=...");
        if ((mode.equals("all") || mode.equals("fbx")) && fbx == null)
            throw new IllegalArgumentException("FBX mode needs an FBX under " + root + " or -Dysm.meshGpuFbx=...");
        var errors = GLFWErrorCallback.createPrint(System.err).set();
        long window = 0;
        try {
            if (!GLFW.glfwInit()) throw new IllegalStateException("GLFW initialization failed");
            // Keep this smoke visible so a developer can inspect the same
            // production draw path that the Forge preview uses.
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, "Oh My YSM real mesh verification", 0, 0);
            if (window == 0) throw new IllegalStateException("No GL context available");
            GLFW.glfwShowWindow(window);
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();
            RenderSystem.initRenderThread();
            System.out.println("Real mesh GL renderer: " + GL11C.glGetString(GL11C.GL_RENDERER));
            System.out.println("Real mesh GL version: " + GL11C.glGetString(GL11C.GL_VERSION));
            if (mode.equals("all") || mode.equals("pmx")) runPmx(pmx, vmd, window);
            if (mode.equals("all") || mode.equals("fbx")) runFbx(fbx, window);
            if (!mode.equals("all") && !mode.equals("pmx") && !mode.equals("fbx"))
                throw new IllegalArgumentException("ysm.meshGpuMode must be all, pmx or fbx");
            if (GL11C.glGetError() != GL11C.GL_NO_ERROR) throw new AssertionError("Real mesh GL error");
            System.out.println("Real PMX/MMD and Kikyo FBX target publication, texture upload, animation session, third-person and first-person draw submission passed");
        } finally {
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            GLFW.glfwSetErrorCallback(null);
            errors.free();
        }
    }

    private static void runPmx(Path model, Path motion, long window) throws Exception {
        GLFW.glfwSetWindowTitle(window, "Oh My YSM | PMX/MMD - read PMX");
        var packageSource = buildPmxPackage(model, motion);
        GLFW.glfwSetWindowTitle(window, "Oh My YSM | PMX/MMD - prepare mesh and textures");
        try (var target = GeneralMeshModelResources.prepare(packageSource, LIMITS, () -> false);
             var host = new GlTextureHost()) {
            GLFW.glfwSetWindowTitle(window, "Oh My YSM | PMX/MMD - upload GPU resources");
            target.publish(host, Integer::intValue, () -> false);
            var animation = target.animations().stream().filter(a -> a.selection().sourceId().equals("motion")).findFirst()
                    .orElseThrow(() -> new AssertionError("PMX VMD animation was not exposed"));
            try (var instance = new GeneralMeshInstance(target, animation.selection(), animation.range(),
                    ScenePackagePlayback.Settings.preview())) {
                instance.timeline().seek(animation.range().start());
                showStage(window, "PMX/MMD - third person", instance, false, STAGE_SECONDS);
                // The supplied VMD is often several minutes long. Seeking to its
                // end would force the physics solver through every fixed step.
                double sampleTime = Math.min(animation.range().end(), animation.range().start() + 1.0);
                instance.timeline().seek(sampleTime);
                showStage(window, "PMX/MMD - first person", instance, true, STAGE_SECONDS);
            }
        }
        System.out.println("Real PMX/MMD: loaded " + model.getFileName() + " with " + Files.size(model) + " bytes and VMD preview");
    }

    static ScenePackage buildPmxPackage(Path model, Path motion) throws Exception {
        String owner = model.getFileName().toString();
        byte[] modelBytes = Files.readAllBytes(model);
        var document = YsmRuntime.scenes().readPmx(new ByteData(modelBytes), LIMITS);
        var files = new LinkedHashMap<String, ByteData>();
        files.put(owner, new ByteData(modelBytes));
        var relocations = new ArrayList<ScenePackage.Relocation>();
        Path root = model.getParent();
        for (String reference : document.textures()) {
            if (reference == null || reference.isBlank()) continue;
            String normalized = reference.replace('\\', '/');
            Path candidate = root.resolve(normalized).normalize();
            if (!Files.isRegularFile(candidate)) candidate = findByName(root, Path.of(normalized).getFileName().toString());
            if (candidate == null || !Files.isRegularFile(candidate)) continue;
            String target = root.relativize(candidate).toString().replace('\\', '/');
            files.putIfAbsent(target, new ByteData(Files.readAllBytes(candidate)));
            relocations.add(new ScenePackage.Relocation(owner, reference, target));
        }
        files.put("motion.vmd", new ByteData(Files.readAllBytes(motion)));
        long encoded = files.values().stream().mapToLong(ByteData::size).sum();
        System.out.println("PMX package inputs: files=" + files.size() + " encodedBytes=" + encoded
                + " (PMX, VMD and referenced textures only)");
        return new ScenePackage(new ScenePackage.Source("model", owner, ScenePackage.Format.PMX),
                new ScenePackage.Settings(0.08, -1, FbxEvaluation.SkinSpace.BIND_WORLD),
                List.of(new ScenePackage.Source("motion", "motion.vmd", ScenePackage.Format.VMD)), relocations, files);
    }

    private static void runFbx(Path model, long window) throws Exception {
        // FBX package assembly and CPU preparation may include many external
        // images. Keep it off the GLFW/render thread so the visible window can
        // continue servicing Windows messages while the worker parses it.
        GLFW.glfwSetWindowTitle(window, "Oh My YSM | FBX - scan referenced files");
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "oh-my-ysm-fbx-prep");
            thread.setDaemon(true);
            return thread;
        });
        AtomicReference<String> status = new AtomicReference<>("Oh My YSM | FBX - scan referenced files");
        Future<GeneralMeshModelResources> prepared = executor.submit(() -> {
            ScenePackage packageSource = buildFbxPackage(model);
            status.set("Oh My YSM | FBX - prepare mesh and textures");
            return GeneralMeshModelResources.prepare(packageSource, LIMITS, () -> false);
        });
        try {
            while (!prepared.isDone()) {
                GLFW.glfwSetWindowTitle(window, status.get());
                GL11C.glViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
                GL11C.glClearColor(.025f, .025f, .04f, 1);
                GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
                GLFW.glfwSwapBuffers(window);
                GLFW.glfwPollEvents();
                if (GLFW.glfwWindowShouldClose(window)) {
                    prepared.cancel(true);
                    return;
                }
                Thread.sleep(16);
            }
        } finally {
            executor.shutdownNow();
        }
        GLFW.glfwSetWindowTitle(window, "Oh My YSM | FBX - upload GPU resources");
        try (var target = prepared.get();
             var host = new GlTextureHost()) {
            target.publish(host, Integer::intValue, () -> false);
            try (var instance = new GeneralMeshInstance(target, ScenePackagePlayback.Selection.REST,
                    new AnimationPreview.Range(0, 1, 30), ScenePackagePlayback.Settings.preview())) {
                showStage(window, "FBX - third person", instance, false, STAGE_SECONDS);
                showStage(window, "FBX - first person", instance, true, STAGE_SECONDS);
            }
        }
        System.out.println("Real FBX: loaded " + model.getFileName() + " with " + Files.size(model) + " bytes and first/third-person draw submission");
    }

    private static ScenePackage buildFbxPackage(Path model) throws Exception {
        Path packageRoot = model.getParent().getParent() == null ? model.getParent() : model.getParent().getParent();
        String owner = packageRoot.relativize(model).toString().replace('\\', '/');
        var relocations = new ArrayList<ScenePackage.Relocation>();
        // Keep the verification package bounded. The source directory also
        // contains PSDs, Unity archives and a Blender file that are not FBX
        // dependencies. Loading those into ByteData was the reason the Java
        // process reached several gigabytes despite the model directory being
        // only a few hundred megabytes on disk.
        byte[] modelBytes = Files.readAllBytes(model);
        var source = YsmRuntime.scenes().readFbx(new ByteData(modelBytes), LIMITS);
        var files = new LinkedHashMap<String, ByteData>();
        files.put(owner, new ByteData(modelBytes));
        for (var texture : source.textures()) {
            if (texture.type() != 0) continue;
            String reference = texture.relativePath().isEmpty() ? texture.absolutePath() : texture.relativePath();
            String name = Path.of(reference.replace('\\', '/')).getFileName().toString();
            Path texturePath;
            try (var paths = Files.walk(packageRoot)) {
                texturePath = paths.filter(Files::isRegularFile)
                        .filter(value -> value.getFileName().toString().equalsIgnoreCase(name))
                        .findFirst().orElse(null);
            }
            if (texturePath != null) {
                String target = packageRoot.relativize(texturePath).toString().replace('\\', '/');
                files.putIfAbsent(target, new ByteData(Files.readAllBytes(texturePath)));
                relocations.add(new ScenePackage.Relocation(owner, reference, target));
            }
        }
        long encoded = files.values().stream().mapToLong(ByteData::size).sum();
        System.out.println("FBX package inputs: files=" + files.size() + " encodedBytes=" + encoded
                + " (only model plus referenced textures)");
        return new ScenePackage(new ScenePackage.Source("model", owner, ScenePackage.Format.FBX),
                new ScenePackage.Settings(1, -1, FbxEvaluation.SkinSpace.BIND_WORLD), List.of(), relocations, files);
    }

    private static void showStage(long window, String title, GeneralMeshInstance instance, boolean firstPerson,
                                  int seconds) throws InterruptedException {
        GLFW.glfwSetWindowTitle(window, "Oh My YSM | " + title);
        // Upload/deform and submit one complete frame. Repeating a full MMD
        // upload on every 16 ms UI tick can starve the native message pump and
        // makes Windows label the inspection window as unresponsive. The
        // stage loop below keeps the submitted frame on screen while it pumps
        // events, which is the useful part of this GPU smoke test.
        draw(instance, firstPerson);
        int visiblePixels = visiblePixels();
        if (visiblePixels == 0) throw new IllegalStateException("No non-background pixels were rendered during " + title);
        System.out.println("Visible GPU pixels: " + title + " = " + visiblePixels);
        GLFW.glfwSwapBuffers(window);
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (!GLFW.glfwWindowShouldClose(window) && System.nanoTime() < deadline) {
            GLFW.glfwPollEvents();
            Thread.sleep(16);
        }
        System.out.println("Visible GPU stage complete: " + title);
    }

    private static int visiblePixels() {
        ByteBuffer pixels = ByteBuffer.allocateDirect(WINDOW_WIDTH * WINDOW_HEIGHT * 4);
        GL11C.glReadPixels(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);
        int visible = 0;
        for (int i = 0; i < pixels.capacity(); i += 4) {
            int r = pixels.get(i) & 0xFF, g = pixels.get(i + 1) & 0xFF, b = pixels.get(i + 2) & 0xFF;
            // Clear color is approximately (9,14,23). Count pixels that differ
            // enough to prove that a surface shader wrote the framebuffer.
            if (Math.abs(r - 9) + Math.abs(g - 14) + Math.abs(b - 23) > 12) visible++;
        }
        return visible;
    }

    private static void draw(GeneralMeshInstance instance, boolean firstPerson) {
        GL11C.glViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(true);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glClearColor(.035f, .055f, .09f, 1);
        GL11C.glClearDepth(1);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
        var view = viewFor(instance, firstPerson);
        instance.render(firstPerson, view);
    }

    /** The old identity view left PMX vertices outside clip space. Frame the
     * actual source bounds after applying the same MMD left-to-right handed and
     * metres-per-unit conversion used by the game host. */
    static GltfSurfaceProgram.View viewFor(GeneralMeshInstance instance, boolean firstPerson) {
        var frame = instance.frame();
        var geometry = firstPerson ? frame.firstPerson() : frame.thirdPerson();
        Matrix4 source = sourceTransform(instance);
        Bounds bounds = bounds(geometry, source);
        if (bounds.empty()) {
            // Some assets have no arm-only first-person variant. Keep the
            // camera useful for diagnostics even though that pass submits no
            // geometry; the log below makes the condition explicit.
            geometry = frame.thirdPerson();
            bounds = bounds(geometry, source);
            System.out.println("Standalone view: first-person geometry is empty; using third-person framing");
        }
        if (bounds.empty()) throw new IllegalStateException("Standalone view found no drawable PMX vertices");
        float cx = (bounds.minX + bounds.maxX) * .5f;
        float cy = (bounds.minY + bounds.maxY) * .5f;
        float cz = (bounds.minZ + bounds.maxZ) * .5f;
        float span = Math.max(Math.max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY), .01f);
        float half = span * .62f;
        float cameraZ = bounds.maxZ + Math.max(.25f, span * 2.5f);
        float near = .01f;
        float far = Math.max(10f, cameraZ - bounds.minZ + span);
        Matrix4 modelView = translation(-cx, -cy, -cameraZ).multiply(source);
        Matrix4 projection = orthographic(-half, half, -half, half, near, far);
        System.out.printf(Locale.ROOT, "Standalone view: %s bounds=(%.3f, %.3f, %.3f)-(%.3f, %.3f, %.3f) span=%.3f%n",
                firstPerson ? "first-person" : "third-person", bounds.minX, bounds.minY, bounds.minZ,
                bounds.maxX, bounds.maxY, bounds.maxZ, span);
        return new GltfSurfaceProgram.View(modelView, projection,
                new Vec3(.3f, .7f, 1), new Vec3(1.0f, 1.0f, 1.0f),
                new Vec3(.35f, .35f, .35f), new FloatData(1, 1, 1, 1), true);
    }

    private static Matrix4 sourceTransform(GeneralMeshInstance instance) {
        var coordinates = instance.frame().thirdPerson().coordinates();
        float scale = (float) instance.assets().source().settings().metersPerUnit();
        float z = coordinates.rightHanded() ? scale : -scale;
        return new Matrix4(scale, 0, 0, 0, 0, scale, 0, 0, 0, 0, z, 0, 0, 0, 0, 1);
    }

    private static Bounds bounds(GeometryFrame frame, Matrix4 source) {
        Bounds result = new Bounds();
        for (var draw : frame.draws()) {
            if (!draw.visible()) continue;
            Matrix4 transform = source.multiply(draw.world());
            for (var primitive : draw.geometry().primitives()) {
                var positions = primitive.attributes().get("POSITION").values();
                for (int i = 0; i < positions.size(); i += 3)
                    result.include(transform.transformPoint(new Vec3(positions.get(i), positions.get(i + 1), positions.get(i + 2))));
            }
        }
        return result;
    }

    private static Matrix4 translation(float x, float y, float z) {
        return new Matrix4(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1);
    }

    private static Matrix4 orthographic(float left, float right, float bottom, float top, float near, float far) {
        return new Matrix4(2 / (right - left), 0, 0, 0, 0, 2 / (top - bottom), 0, 0,
                0, 0, -2 / (far - near), 0, -(right + left) / (right - left),
                -(top + bottom) / (top - bottom), -(far + near) / (far - near), 1);
    }

    private static final class Bounds {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        void include(Vec3 value) { minX = Math.min(minX, value.x()); minY = Math.min(minY, value.y()); minZ = Math.min(minZ, value.z());
            maxX = Math.max(maxX, value.x()); maxY = Math.max(maxY, value.y()); maxZ = Math.max(maxZ, value.z()); }
        boolean empty() { return !Float.isFinite(minX); }
    }

    private static Path find(Path root, String extension) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
                    .sorted().findFirst().orElse(null);
        }
    }

    private static Path inputPath(Path root, String property, String extension) throws IOException {
        String configured = System.getProperty(property);
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (!path.isAbsolute()) path = root.resolve(path);
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Missing " + property + ": " + path);
            return path.normalize();
        }
        return find(root, extension);
    }

    private static Path findByName(Path root, String name) throws IOException {
        if (name == null || name.isBlank()) return null;
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                    .sorted().findFirst().orElse(null);
        }
    }

    private static Map<String, ByteData> files(Path root) throws IOException {
        var result = new LinkedHashMap<String, ByteData>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList())
                result.put(root.relativize(path).toString().replace('\\', '/'), new ByteData(Files.readAllBytes(path)));
        }
        return result;
    }

    static final class GlTextureHost implements SceneTexturePublication.Host<Integer>, AutoCloseable {
        private final Set<Integer> textures = new LinkedHashSet<>();
        @Override public Integer register(PreparedSceneTextures.Pixels pixels) {
            int id = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA32F, pixels.width(), pixels.height(), 0,
                    GL11C.GL_RGBA, GL11C.GL_FLOAT, pixels.rgba().copy());
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, pixels.sampler().minFilter());
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, pixels.sampler().magFilter());
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, pixels.sampler().wrapS());
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, pixels.sampler().wrapT());
            if (pixels.sampler().minFilter() == GL11C.GL_LINEAR_MIPMAP_LINEAR || pixels.sampler().minFilter() == GL11C.GL_LINEAR_MIPMAP_NEAREST
                    || pixels.sampler().minFilter() == GL11C.GL_NEAREST_MIPMAP_LINEAR || pixels.sampler().minFilter() == GL11C.GL_NEAREST_MIPMAP_NEAREST)
                GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
            textures.add(id); return id;
        }
        @Override public void release(Integer id) { if (textures.remove(id)) GL11C.glDeleteTextures(id); }
        @Override public void close() { for (int id : List.copyOf(textures)) release(id); }
    }
}
