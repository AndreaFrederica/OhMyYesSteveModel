package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.vrm.VrmMaterials;
import com.elfmcys.ysm.model.resource.client.render.PreparedSceneTextures;
import com.elfmcys.ysm.model.resource.client.render.VrmTextureBindings;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.ToIntFunction;

/** Core MToon 0/1 surface.  Source material values stay in Lib; this class only owns GPU translation. */
public final class VrmSurfaceProgram implements AutoCloseable {
    public record View(Matrix4 modelView, Matrix4 projection, Vec3 toLight, Vec3 lightRadiance,
                        Vec3 ambient, FloatData tint, boolean orthographic) {}
    private record Slot(String semantic, String uniform, String define) {}
    private static final List<Slot> SLOTS = List.of(
            new Slot("baseColor", "Base", "BASE"),
            new Slot("_MainTex", "Base", "BASE"),
            new Slot("normal", "Normal", "NORMAL"),
            new Slot("_BumpMap", "Normal", "NORMAL"),
            new Slot("emissive", "Emissive", "EMISSIVE"),
            new Slot("_EmissionMap", "Emissive", "EMISSIVE"),
            new Slot("VRMC_materials_mtoon/shadeMultiplyTexture", "Shade", "SHADE"),
            new Slot("_ShadeTexture", "Shade", "SHADE"),
            new Slot("VRMC_materials_mtoon/shadingShiftTexture", "Shift", "SHIFT"),
            new Slot("VRMC_materials_mtoon/matcapTexture", "Matcap", "MATCAP"),
            new Slot("_SphereAdd", "Matcap", "MATCAP"),
            new Slot("VRMC_materials_mtoon/rimMultiplyTexture", "Rim", "RIM"),
            new Slot("_RimTexture", "Rim", "RIM"),
            new Slot("VRMC_materials_mtoon/uvAnimationMaskTexture", "UvMask", "UVMASK"),
            new Slot("_UvAnimMaskTexture", "UvMask", "UVMASK"),
            new Slot("VRMC_materials_mtoon/outlineWidthMultiplyTexture", "OutlineWidth", "OUTLINE_WIDTH"),
            new Slot("_OutlineWidthTexture", "OutlineWidth", "OUTLINE_WIDTH"),
            new Slot("_ReceiveShadowTexture", "ReceiveShadow", "RECEIVE_SHADOW"),
            new Slot("_ShadingGradeTexture", "ShadingGrade", "SHADING_GRADE"));
    private final Map<String, Integer> layout;
    private final Map<String, Integer> uvSets;
    private final List<Slot> slots;
    private final Map<String, Integer> uniforms = new HashMap<>();
    private final int program;
    private final VrmMaterials.Shader shader;
    private boolean closed, bound;

    public VrmSurfaceProgram(MeshAsset.Primitive geometry, VrmMaterials.Material material) throws IOException {
        RenderSystem.assertOnRenderThread();
        shader = Objects.requireNonNull(material.shader());
        if (shader == VrmMaterials.Shader.CUSTOM) {
            throw new IllegalArgumentException("VRM custom material requires an explicit host shader adapter");
        }
        boolean normal = geometry.attributes().containsKey("NORMAL");
        boolean tangent = normal && geometry.attributes().containsKey("TANGENT");
        boolean triangle = switch (geometry.topology()) {
            case TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN -> true;
            default -> false;
        };
        if (triangle && !normal) throw new IllegalArgumentException("VRM triangle needs a normal");
        var attributes = new LinkedHashMap<String, Integer>(); attributes.put("POSITION", 0);
        var declarations = new StringBuilder();
        if (normal) { attributes.put("NORMAL", attributes.size()); declarations.append("#define HAS_NORMAL\n"); }
        if (tangent) { attributes.put("TANGENT", attributes.size()); declarations.append("#define HAS_TANGENT\n"); }
        if (geometry.attributes().containsKey("COLOR_0")) {
            int components = geometry.attributes().get("COLOR_0").components();
            if (components != 3 && components != 4) throw new IllegalArgumentException("VRM vertex color requires VEC3/VEC4");
            attributes.put("COLOR_0", attributes.size()); declarations.append("#define HAS_COLOR\n");
        }
        var chosen = new ArrayList<Slot>(); var uv = new LinkedHashMap<String, Integer>();
        for (var candidate : SLOTS) {
            var texture = material.textures().get(candidate.semantic());
            if (texture == null || candidate.semantic().equals("normal") && !tangent
                    || candidate.semantic().equals("_BumpMap") && !tangent) continue;
            String input = "TEXCOORD_" + texture.binding().texCoord();
            var attribute = geometry.attributes().get(input);
            if (attribute == null || attribute.components() != 2) throw new IllegalArgumentException("VRM material requires VEC2 " + input);
            if (chosen.stream().anyMatch(slot -> slot.uniform().equals(candidate.uniform()))) continue;
            uv.computeIfAbsent(input, key -> { attributes.put(key, attributes.size()); return uv.size(); });
            declarations.append("#define ").append(candidate.define()).append("_MAP\n#define ")
                    .append(candidate.define()).append("_UV Uv").append(uv.get(input)).append('\n');
            int uvMode = switch (texture.uvRule()) {
                case MTOON_0 -> 2;
                case MTOON_1 -> 1;
                default -> 0;
            };
            declarations.append("#define ").append(candidate.define()).append("_UV_MODE ").append(uvMode).append('\n');
            if (texture.channel() == 2) declarations.append("#define ").append(candidate.define()).append("_BLUE_CHANNEL\n");
            declarations.append("#define ").append(candidate.define()).append("_UV_VALUE animationUv((")
                    .append(candidate.uniform()).append("UvTransform * vec3(")
                    .append(candidate.define()).append("_UV, 1.0)).xy, ")
                    .append(candidate.define()).append("_UV_MODE)\n");
            chosen.add(candidate);
        }
        if (attributes.size() > GL11C.glGetInteger(GL20C.GL_MAX_VERTEX_ATTRIBS)
                || chosen.size() > GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS))
            throw new IllegalArgumentException("VRM material exceeds host shader capabilities");
        layout = Map.copyOf(attributes); uvSets = Map.copyOf(uv); slots = List.copyOf(chosen);
        var vertexUv = new StringBuilder(); var fragmentUv = new StringBuilder(); var forwardUv = new StringBuilder();
        for (int index : uv.values()) {
            vertexUv.append("in vec2 InputUv").append(index).append("; out vec2 Uv").append(index).append(";\n");
            fragmentUv.append("in vec2 Uv").append(index).append(";\n");
            forwardUv.append("Uv").append(index).append(" = InputUv").append(index).append(";\n");
        }
        int vertex = 0, fragment = 0, candidate = 0;
        try {
            vertex = compile(GL20C.GL_VERTEX_SHADER, read("vrm_surface.vert").replace("/*DEFINES*/", declarations)
                    .replace("/*UV_DECLARATIONS*/", vertexUv).replace("/*UV_FORWARD*/", forwardUv));
            fragment = compile(GL20C.GL_FRAGMENT_SHADER, read("vrm_surface.frag").replace("/*DEFINES*/", declarations)
                    .replace("/*UV_DECLARATIONS*/", fragmentUv));
            candidate = GL20C.glCreateProgram(); GL20C.glAttachShader(candidate, vertex); GL20C.glAttachShader(candidate, fragment);
            for (var entry : attributes.entrySet()) {
                String name = switch (entry.getKey()) {
                    case "POSITION" -> "Position"; case "NORMAL" -> "Normal"; case "TANGENT" -> "Tangent";
                    case "COLOR_0" -> "VertexColor"; default -> "InputUv" + uv.get(entry.getKey());
                };
                GL20C.glBindAttribLocation(candidate, entry.getValue(), name);
            }
            GL30C.glBindFragDataLocation(candidate, 0, "Color"); GL20C.glLinkProgram(candidate);
            if (GL20C.glGetProgrami(candidate, GL20C.GL_LINK_STATUS) == 0)
                throw new IOException("Cannot link VRM surface: " + GL20C.glGetProgramInfoLog(candidate));
            program = candidate;
            checkGl("program construction");
        } catch (Exception | Error failure) {
            if (candidate != 0) GL20C.glDeleteProgram(candidate);
            throw failure;
        } finally {
            if (vertex != 0) GL20C.glDeleteShader(vertex); if (fragment != 0) GL20C.glDeleteShader(fragment);
        }
    }
    public Map<String, Integer> layout() { return layout; }
    public PreparedBinding prepare(VrmMaterials.Material material, VrmTextureBindings textures,
                                   ToIntFunction<PreparedSceneTextures.Key> ids, View view) {
        requireOpen(); textures.validate(material);
        if (material.shader() != shader) throw new IllegalArgumentException("VRM shader variant changed");
        var resolved = new int[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            var key = textures.textures().get(slots.get(i).semantic());
            if (key == null) throw new IllegalArgumentException("Missing VRM texture binding: " + slots.get(i).semantic());
            int id = ids.applyAsInt(key);
            if (id == 0 || !GL11C.glIsTexture(id)) throw new IllegalStateException("VRM texture is not published");
            resolved[i] = id;
        }
        return new PreparedBinding(material, view, resolved);
    }
    public final class PreparedBinding {
        private final VrmMaterials.Material material; private final View view; private final int[] ids;
        private PreparedBinding(VrmMaterials.Material material, View view, int[] ids) { this.material = material; this.view = view; this.ids = ids; }
        public Binding bind() { return bind(false); }
        public Binding bind(boolean outline) {
            requireOpen(); if (bound) throw new IllegalStateException("Nested VRM shader binding");
            var scope = new Binding(); bound = true;
            try {
                GL20C.glUseProgram(program);
                checkGl("use program");
                GL20C.glUniformMatrix4fv(uniform("ModelView"), false, view.modelView().copy());
                GL20C.glUniformMatrix4fv(uniform("Projection"), false, view.projection().copy());
                var inverse = view.modelView().inverse(); float[] normal = new float[9];
                for (int c = 0; c < 3; c++) for (int r = 0; r < 3; r++) normal[c * 3 + r] = inverse.get(r, c);
                GL20C.glUniformMatrix3fv(uniform("NormalMatrix"), false, normal);
                checkGl("matrices");
                rgba("BaseColor", parameter(material, "baseColor", "_Color", 1, 1, 1, 1));
                rgb("ShadeColor", parameter(material, "VRMC_materials_mtoon/shadeColorFactor", "_ShadeColor", 1, 1, 1));
                var emission = parameter(material, "emissive", "_EmissionColor", 0, 0, 0, 1); rgb("Emission", emission);
                scalar("ShadeShift", scalar(material, "VRMC_materials_mtoon/shadingShiftFactor", "_ShadeShift", 0));
                scalar("ShadeToony", scalar(material, "VRMC_materials_mtoon/shadingToonyFactor", "_ShadeToony", .9f));
                rgb("RimColor", parameter(material, "VRMC_materials_mtoon/parametricRimColorFactor", "_RimColor", 0, 0, 0, 1));
                scalar("RimPower", scalar(material, "VRMC_materials_mtoon/parametricRimFresnelPowerFactor", "_RimFresnelPower", 5));
                integer("AlphaMode", switch (material.renderState().alphaMode()) { case "OPAQUE" -> 0; case "MASK" -> 1; case "BLEND" -> 2; default -> throw new IllegalArgumentException("Invalid VRM alpha mode"); });
                scalar("AlphaCutoff", material.renderState().alphaCutoff()); integer("Unlit", material.shader() == VrmMaterials.Shader.LEGACY_UNLIT ? 1 : 0);
                integer("Orthographic", view.orthographic() ? 1 : 0); rgba("HostTint", view.tint()); rgb("ToLight", view.toLight());
                rgb("LightRadiance", view.lightRadiance()); rgb("Ambient", view.ambient());
                integer("OutlinePass", outline ? 1 : 0);
                integer("OutlineMode", switch (material.renderState().outlineMode()) {
                    case "none" -> 0;
                    case "worldCoordinates" -> 1;
                    case "screenCoordinates" -> 2;
                    default -> throw new IllegalArgumentException("Invalid VRM outline mode");
                });
                scalar("OutlineWidth", scalar(material, "VRMC_materials_mtoon/outlineWidthFactor", "_OutlineWidth", 0));
                rgb("OutlineColor", parameter(material, "VRMC_materials_mtoon/outlineColorFactor", "_OutlineColor", 0, 0, 0));
                scalar("OutlineLightingMix", scalar(material, "VRMC_materials_mtoon/outlineLightingMixFactor", "_OutlineLightingMix", 1));
                rgb("MatcapColor", parameter(material, "VRMC_materials_mtoon/matcapFactor", "_MatCapMul", 0, 0, 0));
                scalar("Time", (float) material.seconds());
                scalar("AnimationScrollX", scalar(material, "VRMC_materials_mtoon/uvAnimationScrollXSpeedFactor", "_UvAnimScrollX", 0));
                scalar("AnimationScrollY", scalar(material, "VRMC_materials_mtoon/uvAnimationScrollYSpeedFactor", "_UvAnimScrollY", 0));
                scalar("AnimationRotation", scalar(material, "VRMC_materials_mtoon/uvAnimationRotationSpeedFactor", "_UvAnimRotation", 0));
                checkGl("parameters");
                for (int unit = 0; unit < slots.size(); unit++) {
                    var slot = slots.get(unit); var texture = material.textures().get(slot.semantic());
                    GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit); GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, ids[unit]);
                    GL20C.glUniform1i(uniform(slot.uniform() + "Texture"), unit);
                    GL20C.glUniformMatrix3fv(uniform(slot.uniform() + "UvTransform"), false, texture.binding().uvTransform().copy());
                }
                checkGl("textures");
                return scope;
            } catch (RuntimeException | Error failure) { scope.close(); throw failure; }
        }
    }
    public final class Binding implements AutoCloseable {
        private final int oldProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), oldUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        private final int[] texture = new int[slots.size()]; private boolean released;
        private Binding() { try { for (int i = 0; i < texture.length; i++) { GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i); texture[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D); } } finally { GL13C.glActiveTexture(oldUnit); } }
        public void close() { RenderSystem.assertOnRenderThread(); if (released) return; released = true; for (int i = 0; i < texture.length; i++) { GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + i); GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture[i]); } GL13C.glActiveTexture(oldUnit); GL20C.glUseProgram(oldProgram); bound = false; }
    }
    private int uniform(String name) { return uniforms.computeIfAbsent(name, key -> GL20C.glGetUniformLocation(program, key)); }
    private void integer(String name, int value) { int location=uniform(name); if(location>=0) GL20C.glUniform1i(location, value); }
    private void scalar(String name, float value) { int location=uniform(name); if(location>=0) GL20C.glUniform1f(location, value); }
    private void rgb(String name, Vec3 value) { int location=uniform(name); if(location>=0) GL20C.glUniform3f(location, value.x(), value.y(), value.z()); }
    private void rgb(String name, FloatData value) { int location=uniform(name); if(location>=0) GL20C.glUniform3f(location, value.get(0), value.get(1), value.get(2)); }
    private void rgba(String name, FloatData value) { if (value.size() < 3) throw new IllegalArgumentException("VRM color needs at least RGB: " + name); int location=uniform(name); if(location>=0) GL20C.glUniform4f(location, value.get(0), value.get(1), value.get(2), value.size() > 3 ? value.get(3) : 1); }
    private static FloatData parameter(VrmMaterials.Material material, String modern, String legacy, float... fallback) { var value = material.parameters().get(material.shader() == VrmMaterials.Shader.MTOON_0 || material.parameterSpace() == VrmMaterials.ParameterSpace.LEGACY_SHADER ? legacy : modern); return value == null ? new FloatData(fallback) : value; }
    private static float scalar(VrmMaterials.Material material, String modern, String legacy, float fallback) { var value = material.parameters().get(material.shader() == VrmMaterials.Shader.MTOON_0 || material.parameterSpace() == VrmMaterials.ParameterSpace.LEGACY_SHADER ? legacy : modern); return value == null || value.size() != 1 ? fallback : value.get(0); }
    private static String read(String file) throws IOException { try (var input = VrmSurfaceProgram.class.getResourceAsStream("/assets/ysm/shaders/general_mesh/" + file)) { if (input == null) throw new IOException("Missing VRM shader: " + file); return new String(input.readAllBytes(), StandardCharsets.UTF_8); } }
    private static void checkGl(String stage) { int error=GL11C.glGetError(); if(error!=GL11C.GL_NO_ERROR) throw new IllegalStateException("VRM shader binding failed at "+stage+": GL "+error); }
    private static int compile(int type, String source) throws IOException { int shader = GL20C.glCreateShader(type); try { GL20C.glShaderSource(shader, source); GL20C.glCompileShader(shader); if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == 0) throw new IOException("Cannot compile VRM shader: " + GL20C.glGetShaderInfoLog(shader)); return shader; } catch (Exception | Error failure) { GL20C.glDeleteShader(shader); throw failure; } }
    private void requireOpen() { RenderSystem.assertOnRenderThread(); if (closed) throw new IllegalStateException("VRM surface program is closed"); }
    @Override public void close() { RenderSystem.assertOnRenderThread(); if (closed) return; if (bound) throw new IllegalStateException("Cannot close a bound VRM program"); closed = true; GL20C.glDeleteProgram(program); }
}
