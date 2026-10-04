package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.mmd.MmdMaterials;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.resource.client.render.MmdTextureBindings;
import com.elfmcys.ysm.model.resource.client.render.PreparedSceneTextures;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.ToIntFunction;

/** MMD surface shader, independent of playback. The caller owns draw order, raster state and framebuffer. */
public final class MmdSurfaceProgram implements AutoCloseable {
    public record View(Matrix4 modelView, Matrix4 projection, Vec3 lightColor, Vec3 toLight, FloatData tint, boolean orthographic) {
        public View(Matrix4 modelView, Matrix4 projection, Vec3 lightColor, Vec3 toLight, FloatData tint) {
            this(modelView,projection,lightColor,toLight,tint,false);
        }
        public View {
            Objects.requireNonNull(modelView); Objects.requireNonNull(projection);
            Objects.requireNonNull(lightColor); Objects.requireNonNull(toLight);
            if (tint.size() != 4) throw new IllegalArgumentException("Host tint needs RGBA");
        }
    }
    private final int program;
    private final boolean additionalUv;
    private final Map<String, Integer> uniforms = new HashMap<>();
    private boolean closed;
    private boolean bound;

    public MmdSurfaceProgram(boolean additionalUv) throws IOException {
        RenderSystem.assertOnRenderThread();
        this.additionalUv = additionalUv;
        int vertex = 0, fragment = 0, candidate = 0;
        try {
            vertex = compile(GL20C.GL_VERTEX_SHADER, read("mmd_surface.vert", additionalUv));
            fragment = compile(GL20C.GL_FRAGMENT_SHADER, read("mmd_surface.frag", additionalUv));
            candidate = GL20C.glCreateProgram();
            GL20C.glAttachShader(candidate, vertex); GL20C.glAttachShader(candidate, fragment);
            GL20C.glBindAttribLocation(candidate, 0, "Position");
            GL20C.glBindAttribLocation(candidate, 1, "Normal");
            GL20C.glBindAttribLocation(candidate, 2, "Uv");
            GL20C.glBindAttribLocation(candidate, 4, "EdgeScale");
            if (additionalUv) GL20C.glBindAttribLocation(candidate, 3, "AdditionalUv");
            GL30C.glBindFragDataLocation(candidate, 0, "Color");
            GL20C.glLinkProgram(candidate);
            if (GL20C.glGetProgrami(candidate, GL20C.GL_LINK_STATUS) == 0)
                throw new IOException("Cannot link MMD surface shader: " + GL20C.glGetProgramInfoLog(candidate));
            program = candidate;
        } catch (Exception | Error failure) {
            if (candidate != 0) GL20C.glDeleteProgram(candidate);
            throw failure;
        } finally {
            if (vertex != 0) GL20C.glDeleteShader(vertex);
            if (fragment != 0) GL20C.glDeleteShader(fragment);
        }
    }

    public Map<String, Integer> layout() {
        return additionalUv ? Map.of("POSITION", 0, "NORMAL", 1, "TEXCOORD_0", 2, "_MMD_UV1", 3, "_MMD_EDGE_SCALE", 4)
                : Map.of("POSITION", 0, "NORMAL", 1, "TEXCOORD_0", 2, "_MMD_EDGE_SCALE", 4);
    }

    /** Scope restores the actual host program, active unit, textures and sampler objects, even on uniform failure. */
    public Binding bind(MmdMaterials.Material material, MmdTextureBindings.Material textures,
                        ToIntFunction<PreparedSceneTextures.Key> textureIds, View view) {
        return bind(material, textures, textureIds, view, false);
    }

    public Binding bindEdge(MmdMaterials.Material material, View view) {
        return bind(material, new MmdTextureBindings.Material(null, null, null), ignored -> 0, view, true);
    }
    public Binding bindEdge(MmdMaterials.Material material, View view,float scale) {
        var binding=bindEdge(material,view);scalar("EdgeSize",material.values().edgeSize()*scale);return binding;
    }

    private Binding bind(MmdMaterials.Material material, MmdTextureBindings.Material textures,
                         ToIntFunction<PreparedSceneTextures.Key> textureIds, View view, boolean edgePass) {
        requireOpen();
        if (bound) throw new IllegalStateException("MMD program is already bound");
        int inheritedError = drainErrors();
        if (inheritedError != GL11C.GL_NO_ERROR) {
            // GL errors are sticky and belong to the whole host context. Do not
            // turn an unrelated earlier renderer error into a model-load crash.
            YesSteveModel.LOGGER.debug("Cleared inherited GL error before MMD binding: {}",
                    inheritedError);
        }
        var definition = material.definition();
        if (definition.points() && !edgePass) {
            float[] range = new float[2]; GL11C.glGetFloatv(GL11C.GL_POINT_SIZE_RANGE, range);
            float size = material.values().edgeSize();
            if (size < range[0] || size > range[1]) throw new IllegalArgumentException("MMD point size is outside host capabilities: " + size);
        }
        if (!additionalUv && (definition.vertexColor() || definition.sphere() != null && definition.sphereMode() == MmdMaterials.SphereMode.SUB_TEXTURE))
            throw new IllegalArgumentException("MMD material requires additional UV1");
        // Verify the transform before changing host GL state.
        var inverse = view.modelView().inverse();
        float[] normalMatrix = new float[9];
        for (int column = 0; column < 3; column++) for (int row = 0; row < 3; row++)
            normalMatrix[column * 3 + row] = inverse.get(row, column);
        var scope = new Binding();
        bound = true;
        try {
            GL20C.glUseProgram(program);
            GL20C.glUniformMatrix4fv(uniform("ModelView"), false, view.modelView().copy());
            GL20C.glUniformMatrix4fv(uniform("Projection"), false, view.projection().copy());
            GL20C.glUniformMatrix3fv(uniform("NormalMatrix"), false, normalMatrix);
            var values = material.values();
            rgba("Diffuse", values.diffuse()); rgb("Ambient", values.ambient()); rgb("Specular", values.specular());
            scalar("Shininess", values.shininess()); rgb("LightColor", view.lightColor()); rgb("ToLight", view.toLight());
            rgba("HostTint", view.tint()); integer("VertexColor", definition.vertexColor() ? 1 : 0);
            integer("Orthographic",view.orthographic()?1:0);
            scalar("PointSize", definition.points() ? values.edgeSize() : 1);
            integer("EdgePass", edgePass ? 1 : 0); scalar("EdgeSize", values.edgeSize()); rgba("EdgeColor", values.edgeColor());
            if (edgePass) {
                int[] viewport = new int[4]; GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
                if (viewport[2] <= 0 || viewport[3] <= 0) throw new IllegalStateException("MMD edge pass requires a viewport");
                GL20C.glUniform2f(uniform("Viewport"), viewport[2], viewport[3]);
            }
            integer("HasDiffuse", textures.diffuse() == null ? 0 : 1);
            integer("SphereMode", textures.sphere() == null ? 0 : definition.sphereMode().ordinal());
            integer("HasToon", textures.toon() == null ? 0 : 1);
            rgba("TextureMultiply", values.textureMultiply()); rgba("TextureAdd", values.textureAdd());
            rgba("SphereMultiply", values.sphereMultiply()); rgba("SphereAdd", values.sphereAdd());
            rgba("ToonMultiply", values.toonMultiply()); rgba("ToonAdd", values.toonAdd());
            bindTexture(0, "DiffuseTexture", textures.diffuse(), textureIds, scope.samplers);
            bindTexture(1, "SphereTexture", textures.sphere(), textureIds, scope.samplers);
            bindTexture(2, "ToonTexture", textures.toon(), textureIds, scope.samplers);
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("MMD shader binding failed: GL " + error);
            return scope;
        } catch (RuntimeException | Error failure) {
            scope.close(); throw failure;
        }
    }

    public final class Binding implements AutoCloseable {
        private final int oldProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        private final int oldUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        private final boolean samplers = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects;
        private final int[] oldTextures = new int[3], oldSamplers = new int[3];
        private boolean released;
        private Binding() {
            try {
                for (int i = 0; i < 3; i++) {
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i);
                    oldTextures[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
                    if (samplers) oldSamplers[i] = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
                }
            } finally { GL13C.glActiveTexture(oldUnit); }
        }
        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            if (released) return;
            released = true;
            for (int i = 0; i < 3; i++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, oldTextures[i]);
                if (samplers) GL33C.glBindSampler(i, oldSamplers[i]);
            }
            GL13C.glActiveTexture(oldUnit); GL20C.glUseProgram(oldProgram);
            bound = false;
        }
    }
    private void bindTexture(int unit, String sampler, PreparedSceneTextures.Key key,
                             ToIntFunction<PreparedSceneTextures.Key> ids, boolean samplers) {
        integer(sampler, unit);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        int id = key == null ? 0 : ids.applyAsInt(key);
        if (key != null && (id == 0 || !GL11C.glIsTexture(id))) throw new IllegalStateException("MMD texture is not published: " + key);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        if (samplers) GL33C.glBindSampler(unit, 0);
    }
    private int uniform(String name) {
        return uniforms.computeIfAbsent(name, key -> GL20C.glGetUniformLocation(program, key));
    }
    private void integer(String name, int value) { GL20C.glUniform1i(uniform(name), value); }
    private void scalar(String name, float value) { GL20C.glUniform1f(uniform(name), value); }
    private void rgb(String name, Vec3 value) { GL20C.glUniform3f(uniform(name), value.x(), value.y(), value.z()); }
    private void rgba(String name, FloatData value) {
        if (value.size() != 4) throw new IllegalArgumentException("MMD shader requires RGBA for " + name);
        GL20C.glUniform4f(uniform(name), value.get(0), value.get(1), value.get(2), value.get(3));
    }
    private static int drainErrors() {
        int first = GL11C.GL_NO_ERROR;
        for (int i = 0; i < 16; i++) {
            int error = GL11C.glGetError();
            if (error == GL11C.GL_NO_ERROR) return first;
            if (first == GL11C.GL_NO_ERROR) first = error;
        }
        return first;
    }
    private static String read(String file, boolean additional) throws IOException {
        String path = "/assets/ysm/shaders/general_mesh/" + file;
        try (var stream = MmdSurfaceProgram.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing MMD shader: " + path);
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return additional ? text.replace("#version 150", "#version 150\n#define ADDITIONAL_UV") : text;
        }
    }
    private static int compile(int type, String source) throws IOException {
        int shader = GL20C.glCreateShader(type);
        try {
            GL20C.glShaderSource(shader, source); GL20C.glCompileShader(shader);
            if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0)
                throw new IOException("Cannot compile MMD shader: " + GL20C.glGetShaderInfoLog(shader));
            return shader;
        } catch (Exception | Error failure) { GL20C.glDeleteShader(shader); throw failure; }
    }
    private void requireOpen() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("MMD surface program is closed");
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        if (bound) throw new IllegalStateException("Cannot close a bound MMD program");
        closed = true; GL20C.glDeleteProgram(program);
    }
}
