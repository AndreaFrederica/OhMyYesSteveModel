package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.api.rendering.v0.SceneLightmap;
import org.lwjgl.opengl.*;

/** One extra sampler after the source material's samplers. Never owns the game's texture. */
final class SceneLightmapUniforms {
    private final int enabled, coordinates, texture;

    static String source(String text) throws java.io.IOException {
        if (!text.contains("/*HOST_LIGHTMAP*/")) return text;
        try (var input = SceneLightmapUniforms.class.getResourceAsStream("/assets/ysm/shaders/general_mesh/host_lightmap.glsl")) {
            if (input == null) throw new java.io.IOException("Missing scene lightmap shader source");
            return text.replace("/*HOST_LIGHTMAP*/", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    SceneLightmapUniforms(int program) {
        enabled = GL20C.glGetUniformLocation(program, "HasHostLightmap");
        coordinates = GL20C.glGetUniformLocation(program, "HostLightCoordinates");
        texture = GL20C.glGetUniformLocation(program, "HostLightmap");
    }

    Binding bind(SceneLightmap lightmap, int unit) {
        GL20C.glUniform1i(enabled, lightmap.enabled() ? 1 : 0);
        GL20C.glUniform2i(coordinates, lightmap.block(), lightmap.sky());
        GL20C.glUniform1i(texture, unit);
        if (!lightmap.enabled()) return null;
        if (!GL11C.glIsTexture(lightmap.textureId()))
            throw new IllegalStateException("Scene host lightmap is not uploaded");
        return new Binding(lightmap.textureId(), unit);
    }

    static final class Binding implements AutoCloseable {
        private final int unit, oldTexture, oldSampler;
        private final boolean samplers = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects;
        private boolean closed;

        private Binding(int textureId, int unit) {
            this.unit = unit;
            int active = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            try {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                oldTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
                oldSampler = samplers ? GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING) : 0;
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textureId);
                if (samplers) GL33C.glBindSampler(unit, 0);
            } finally { GL13C.glActiveTexture(active); }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            int active = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            try {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, oldTexture);
                if (samplers) GL33C.glBindSampler(unit, oldSampler);
            } finally { GL13C.glActiveTexture(active); }
        }
    }
}
