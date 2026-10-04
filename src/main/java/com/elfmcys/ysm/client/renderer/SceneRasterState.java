package com.elfmcys.ysm.client.renderer;

import org.lwjgl.opengl.*;

/** Preserve actual driver state while borrowing the MC context; Minecraft's cached state remains unchanged. */
final class SceneRasterState implements AutoCloseable {
    /*
     * Minecraft can expose the desktop LWJGL entry point while the active
     * profile rejects polygon-mode operations (some core/translation
     * profiles do this).  Calling glPolygonMode merely to force fill then
     * produces a high-severity GL debug error.  Scene rendering already
     * specifies its own fill primitives, so keep this optional state path
     * disabled until a host explicitly opts in.
     */
    private static boolean polygonModeSupported =
            Boolean.getBoolean("ysm.scene.enablePolygonMode");
    private static final int[] CAPS = { GL11C.GL_BLEND, GL11C.GL_CULL_FACE, GL11C.GL_DEPTH_TEST,
            GL32C.GL_PROGRAM_POINT_SIZE, GL30C.GL_FRAMEBUFFER_SRGB, GL13C.GL_SAMPLE_ALPHA_TO_COVERAGE,
            GL11C.GL_POLYGON_OFFSET_FILL, GL11C.GL_POLYGON_OFFSET_LINE, GL11C.GL_POLYGON_OFFSET_POINT };
    private final boolean[] enabled = new boolean[CAPS.length];
    private final int front = GL11C.glGetInteger(GL11C.GL_FRONT_FACE), cull = GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE);
    private final int depth = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
    private final boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
    private final int[] polygon = new int[2];
    private final boolean polygonManaged;
    private final float lineWidth = GL11C.glGetFloat(GL11C.GL_LINE_WIDTH);
    private final int srcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB), dstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
    private final int srcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA), dstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
    private final int equationRgb = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB), equationAlpha = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA);
    SceneRasterState() {
        boolean managePolygon = polygonModeSupported;
        if (managePolygon) {
            GL11C.glGetIntegerv(GL11C.GL_POLYGON_MODE, polygon);
            if (GL11C.glGetError() != GL11C.GL_NO_ERROR) {
                polygonModeSupported = false;
                managePolygon = false;
            }
        }
        polygonManaged = managePolygon;
        for (int i = 0; i < CAPS.length; i++) enabled[i] = GL11C.glIsEnabled(CAPS[i]);
    }
    void configure(boolean clockwise, boolean linearOutput) {
        GL11C.glEnable(GL11C.GL_BLEND); GL11C.glEnable(GL11C.GL_DEPTH_TEST); GL11C.glEnable(GL32C.GL_PROGRAM_POINT_SIZE);
        enabled(GL30C.GL_FRAMEBUFFER_SRGB, linearOutput); GL11C.glDisable(GL13C.GL_SAMPLE_ALPHA_TO_COVERAGE);
        GL11C.glDisable(GL11C.GL_POLYGON_OFFSET_FILL); GL11C.glDisable(GL11C.GL_POLYGON_OFFSET_LINE); GL11C.glDisable(GL11C.GL_POLYGON_OFFSET_POINT);
        GL11C.glFrontFace(clockwise ? GL11C.GL_CW : GL11C.GL_CCW);
        if (polygonManaged) {
            GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK, GL11C.GL_FILL);
            if (GL11C.glGetError() != GL11C.GL_NO_ERROR) polygonModeSupported = false;
        }
        GL11C.glDepthFunc(GL11C.GL_LEQUAL); GL11C.glDepthMask(true); GL11C.glLineWidth(1);
        GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
        GL14C.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
    }
    static void enabled(int capability, boolean value) { if (value) GL11C.glEnable(capability); else GL11C.glDisable(capability); }
    /** Some host profiles expose polygon mode in the Java bindings but reject it at runtime. */
    static void forceFillIfSupported() {
        if (!polygonModeSupported) return;
        GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK, GL11C.GL_FILL);
        if (GL11C.glGetError() != GL11C.GL_NO_ERROR) polygonModeSupported = false;
    }
    @Override public void close() {
        for (int i = 0; i < CAPS.length; i++) enabled(CAPS[i], enabled[i]);
        GL11C.glFrontFace(front); GL11C.glCullFace(cull); GL11C.glDepthFunc(depth); GL11C.glDepthMask(depthMask);
        if (polygonManaged && polygonModeSupported) {
            if (polygon[0] == polygon[1]) {
                GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK, polygon[0]);
            } else {
                GL11C.glPolygonMode(GL11C.GL_FRONT, polygon[0]);
                GL11C.glPolygonMode(GL11C.GL_BACK, polygon[1]);
            }
            if (GL11C.glGetError() != GL11C.GL_NO_ERROR) polygonModeSupported = false;
        }
        GL11C.glLineWidth(lineWidth);
        GL20C.glBlendEquationSeparate(equationRgb, equationAlpha); GL14C.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
    }
}
