package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.model.resource.client.render.GltfTextureBindings;
import com.elfmcys.ysm.model.resource.client.render.PreparedSceneTextures;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.ToIntFunction;

/** Core glTF metallic/roughness and unlit surface. Owns a shader variant, never an animation or mesh instance. */
public final class GltfSurfaceProgram implements AutoCloseable {
    public record View(Matrix4 modelView, Matrix4 projection, Vec3 toLight, Vec3 lightRadiance,
                       Vec3 diffuseIrradiance, FloatData tint, boolean orthographic) {
        public View(Matrix4 modelView, Matrix4 projection, Vec3 toLight, Vec3 lightRadiance, Vec3 diffuseIrradiance, FloatData tint) {
            this(modelView,projection,toLight,lightRadiance,diffuseIrradiance,tint,false);
        }
        public View {
            Objects.requireNonNull(modelView); Objects.requireNonNull(projection); Objects.requireNonNull(toLight);
            Objects.requireNonNull(lightRadiance); Objects.requireNonNull(diffuseIrradiance);
            if (tint.size() != 4) throw new IllegalArgumentException("Host tint requires RGBA");
        }
    }
    private record TextureSlot(String semantic, String uniform, String define) {}
    private static final List<TextureSlot> SLOTS = List.of(new TextureSlot("baseColor", "Base", "BASE"),
            new TextureSlot("metallicRoughness", "Mr", "MR"), new TextureSlot("normal", "Normal", "NORMAL"),
            new TextureSlot("occlusion", "Occlusion", "OCCLUSION"), new TextureSlot("emissive", "Emissive", "EMISSIVE"));
    private final Map<String, Integer> layout;
    private final Map<String, Integer> uvSets;
    private final Map<String, Integer> textureCoordinates;
    private final List<TextureSlot> slots;
    private final Map<String, Integer> uniforms = new HashMap<>();
    private final int program;
    private final boolean unlit;
    private boolean closed, bound;

    public GltfSurfaceProgram(MeshAsset.Primitive geometry, SceneAsset.Material material) throws IOException {
        RenderSystem.assertOnRenderThread(); validate(material);
        unlit = material.workflow().equals("unlit");
        boolean normal = geometry.attributes().containsKey("NORMAL");
        boolean tangent = normal && geometry.attributes().containsKey("TANGENT");
        boolean triangle = switch (geometry.topology()) { case TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN -> true; default -> false; };
        if (!unlit && triangle && !normal) throw new IllegalArgumentException("glTF triangle needs Lib surface preparation");
        if (!unlit && triangle && material.textures().containsKey("normal") && !tangent)
            throw new IllegalArgumentException("glTF normal map needs Lib MikkTSpace preparation");
        var attributes = new LinkedHashMap<String, Integer>(); attributes.put("POSITION", 0);
        var declarations = new StringBuilder();
        if (normal) { attributes.put("NORMAL", attributes.size()); declarations.append("#define HAS_NORMAL\n"); }
        if (tangent) { attributes.put("TANGENT", attributes.size()); declarations.append("#define HAS_TANGENT\n"); }
        if (geometry.attributes().containsKey("COLOR_0")) {
            int components = geometry.attributes().get("COLOR_0").components();
            if (components != 3 && components != 4) throw new IllegalArgumentException("glTF vertex color requires VEC3 or VEC4");
            attributes.put("COLOR_0", attributes.size()); declarations.append("#define HAS_COLOR\n");
        }
        var chosen = new ArrayList<TextureSlot>(); var uv = new LinkedHashMap<String, Integer>();
        for (var slot : SLOTS) {
            var texture = material.textures().get(slot.semantic());
            if (texture == null || unlit && !slot.semantic().equals("baseColor") || slot.semantic().equals("normal") && !tangent) continue;
            String semantic = "TEXCOORD_" + texture.texCoord();
            var input = geometry.attributes().get(semantic);
            if (input == null || input.components() != 2) throw new IllegalArgumentException("glTF material requires VEC2 " + semantic);
            uv.computeIfAbsent(semantic, key -> { int index = attributes.size(); attributes.put(key, index); return uv.size(); });
            declarations.append("#define ").append(slot.define()).append("_MAP\n#define ").append(slot.define()).append("_UV Uv").append(uv.get(semantic)).append('\n');
            chosen.add(slot);
        }
        if (attributes.size() > GL11C.glGetInteger(GL20C.GL_MAX_VERTEX_ATTRIBS)
                || chosen.size() > GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS))
            throw new IllegalArgumentException("glTF material exceeds host shader input capabilities");
        layout = Collections.unmodifiableMap(attributes); uvSets = Collections.unmodifiableMap(uv); slots = List.copyOf(chosen);
        var coordinateBindings = new LinkedHashMap<String, Integer>();
        material.textures().forEach((key,value)->coordinateBindings.put(key,value.texCoord()));
        textureCoordinates = Map.copyOf(coordinateBindings);
        var vertexUv = new StringBuilder(); var fragmentUv = new StringBuilder(); var forwardUv = new StringBuilder();
        for (int index : uv.values()) {
            vertexUv.append("in vec2 InputUv").append(index).append("; out vec2 Uv").append(index).append(";\n");
            fragmentUv.append("in vec2 Uv").append(index).append(";\n");
            forwardUv.append("Uv").append(index).append(" = InputUv").append(index).append(";\n");
        }
        int vertex = 0, fragment = 0, candidate = 0;
        try {
            vertex = compile(GL20C.GL_VERTEX_SHADER, read("gltf_surface.vert").replace("/*DEFINES*/", declarations)
                    .replace("/*UV_DECLARATIONS*/", vertexUv).replace("/*UV_FORWARD*/", forwardUv));
            fragment = compile(GL20C.GL_FRAGMENT_SHADER, read("gltf_surface.frag").replace("/*DEFINES*/", declarations).replace("/*UV_DECLARATIONS*/", fragmentUv));
            candidate = GL20C.glCreateProgram(); GL20C.glAttachShader(candidate, vertex); GL20C.glAttachShader(candidate, fragment);
            for (var entry : attributes.entrySet()) {
                String name = switch (entry.getKey()) { case "POSITION" -> "Position"; case "NORMAL" -> "Normal"; case "TANGENT" -> "Tangent"; case "COLOR_0" -> "VertexColor"; default -> "InputUv" + uv.get(entry.getKey()); };
                GL20C.glBindAttribLocation(candidate, entry.getValue(), name);
            }
            GL30C.glBindFragDataLocation(candidate, 0, "Color"); GL20C.glLinkProgram(candidate);
            if (GL20C.glGetProgrami(candidate, GL20C.GL_LINK_STATUS) == 0) throw new IOException("Cannot link glTF surface: " + GL20C.glGetProgramInfoLog(candidate));
            program = candidate;
        } catch (Exception | Error failure) { if (candidate != 0) GL20C.glDeleteProgram(candidate); throw failure; }
        finally { if (vertex != 0) GL20C.glDeleteShader(vertex); if (fragment != 0) GL20C.glDeleteShader(fragment); }
    }
    public Map<String, Integer> layout() { return layout; }
    public Binding bind(SceneAsset.Material material, GltfTextureBindings textures, ToIntFunction<PreparedSceneTextures.Key> ids, View view) {
        return prepare(material, textures, ids, view).bind();
    }
    /** Validate and resolve all fallible source inputs before any primitive in the frame is drawn. */
    public PreparedBinding prepare(SceneAsset.Material material, GltfTextureBindings textures, ToIntFunction<PreparedSceneTextures.Key> ids, View view) {
        requireOpen(); validate(material);
        textures.validate(material);
        if (bound || unlit != material.workflow().equals("unlit")) throw new IllegalStateException("glTF shader variant mismatch or nested binding");
        if(!material.textures().keySet().equals(textureCoordinates.keySet())) throw new IllegalArgumentException("glTF texture variant changed");
        for(var entry:material.textures().entrySet()) if(entry.getValue().texCoord()!=textureCoordinates.get(entry.getKey()))
            throw new IllegalArgumentException("glTF texture UV variant changed");
        for (var slot : slots) {
            var texture = material.textures().get(slot.semantic());
            if (texture == null || !uvSets.containsKey("TEXCOORD_" + texture.texCoord())) throw new IllegalArgumentException("glTF material UV variant changed");
        }
        var inverse = view.modelView().inverse(); float[] normal = new float[9];
        for (int c = 0; c < 3; c++) for (int r = 0; r < 3; r++) normal[c * 3 + r] = inverse.get(r, c);
        int[] resolved = new int[slots.size()];
        for (int unit = 0; unit < slots.size(); unit++) {
            String semantic = slots.get(unit).semantic();
            var key = textures.textures().get(semantic);
            if (key == null) throw new IllegalArgumentException("Missing glTF texture binding: " + semantic);
            int id = ids.applyAsInt(key);
            if (id == 0 || !GL11C.glIsTexture(id)) throw new IllegalStateException("glTF texture is not published: " + key);
            resolved[unit] = id;
        }
        return new PreparedBinding(material, view, normal, resolved);
    }
    public final class PreparedBinding {
        private final SceneAsset.Material material;
        private final View view;
        private final float[] normal;
        private final int[] ids;
        private PreparedBinding(SceneAsset.Material material, View view, float[] normal, int[] ids) {
            this.material = material; this.view = view; this.normal = normal; this.ids = ids;
        }
        public Binding bind() {
            requireOpen();
            if (bound) throw new IllegalStateException("Nested glTF binding");
            return bindPrepared(this);
        }
    }
    private Binding bindPrepared(PreparedBinding prepared) {
        var material = prepared.material; var view = prepared.view; var normal = prepared.normal;
        var scope = new Binding(); bound = true;
        try {
            GL20C.glUseProgram(program);
            GL20C.glUniformMatrix4fv(uniform("ModelView"), false, view.modelView().copy());
            GL20C.glUniformMatrix4fv(uniform("Projection"), false, view.projection().copy());
            GL20C.glUniformMatrix3fv(uniform("NormalMatrix"), false, normal);
            var m = view.modelView();
            double determinant = m.get(0,0)*(m.get(1,1)*m.get(2,2)-m.get(1,2)*m.get(2,1))
                    -m.get(1,0)*(m.get(0,1)*m.get(2,2)-m.get(0,2)*m.get(2,1))+m.get(2,0)*(m.get(0,1)*m.get(1,2)-m.get(0,2)*m.get(1,1));
            scalar("TransformSign", determinant < 0 ? -1 : 1);
            GL20C.glUniform4fv(uniform("BaseColor"), parameter(material,"baseColor",1,1,1,1).copy());
            scalar("Metallic",parameter(material,"metallic",1).get(0));scalar("Roughness",parameter(material,"roughness",1).get(0));
            var emission=parameter(material,"emissive",0,0,0);float strength=parameter(material,"KHR_materials_emissive_strength/emissiveStrength",1).get(0);
            GL20C.glUniform3f(uniform("Emissive"),emission.get(0)*strength,emission.get(1)*strength,emission.get(2)*strength);
            integer("AlphaMode",switch(material.alphaMode()) { case "OPAQUE"->0;case "MASK"->1;case "BLEND"->2;default->throw new IllegalArgumentException("Invalid glTF alpha mode"); });
            scalar("AlphaCutoff",material.alphaCutoff());integer("Unlit",unlit?1:0);
            integer("Orthographic",view.orthographic()?1:0);
            GL20C.glUniform4fv(uniform("HostTint"),view.tint().copy());rgb("ToLight",view.toLight());rgb("LightRadiance",view.lightRadiance());rgb("DiffuseIrradiance",view.diffuseIrradiance());
            for(int unit=0;unit<slots.size();unit++) {
                var slot=slots.get(unit);var texture=material.textures().get(slot.semantic());
                int id=prepared.ids[unit];
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0+unit);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,id);
                if(scope.samplers) GL33C.glBindSampler(unit,0);
                integer(slot.uniform()+"Texture",unit);GL20C.glUniformMatrix3fv(uniform(slot.uniform()+"UvTransform"),false,texture.uvTransform().copy());
                if(slot.semantic().equals("normal")) scalar("NormalScale",texture.scale());
                if(slot.semantic().equals("occlusion")) scalar("OcclusionStrength",texture.scale());
            }
            int error=GL11C.glGetError();if(error!=GL11C.GL_NO_ERROR) throw new IllegalStateException("glTF shader binding failed: GL "+error);
            return scope;
        } catch(RuntimeException|Error failure) { scope.close();throw failure; }
    }
    public final class Binding implements AutoCloseable {
        private final int oldProgram=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),oldUnit=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        private final boolean samplers=GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects;
        private final int[] texture=new int[slots.size()],sampler=new int[slots.size()];private boolean released;
        private Binding() {
            try { for(int i=0;i<texture.length;i++) { GL13C.glActiveTexture(GL13C.GL_TEXTURE0+i);texture[i]=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);if(samplers) sampler[i]=GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING); } }
            finally { GL13C.glActiveTexture(oldUnit); }
        }
        public void close() {
            RenderSystem.assertOnRenderThread();if(released) return;released=true;
            for(int i=0;i<texture.length;i++) { GL13C.glActiveTexture(GL13C.GL_TEXTURE0+i);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,texture[i]);if(samplers) GL33C.glBindSampler(i,sampler[i]); }
            GL13C.glActiveTexture(oldUnit);GL20C.glUseProgram(oldProgram);bound=false;
        }
    }
    private static void validate(SceneAsset.Material material) {
        if(!Set.of("unlit","metallic-roughness").contains(material.workflow())) throw new IllegalArgumentException("Not a core glTF material");
        if(!Set.of("OPAQUE","MASK","BLEND").contains(material.alphaMode())) throw new IllegalArgumentException("Invalid glTF alpha mode");
        parameter(material,"baseColor",1,1,1,1); parameter(material,"metallic",1); parameter(material,"roughness",1);
        parameter(material,"emissive",0,0,0); parameter(material,"KHR_materials_emissive_strength/emissiveStrength",1);
        String extensions=material.metadata().get("extensions");
        if(extensions!=null) for(String extension:JsonParser.parseString(extensions).getAsJsonObject().keySet())
            if(!Set.of("KHR_materials_unlit","KHR_materials_emissive_strength").contains(extension)) throw new IllegalArgumentException("glTF material requires its extension renderer: "+extension);
    }
    private static FloatData parameter(SceneAsset.Material material,String name,float... fallback) {
        var value=material.parameters().getOrDefault(name,new FloatData(fallback));
        if(value.size()!=fallback.length) throw new IllegalArgumentException("Invalid glTF material parameter: "+name);return value;
    }
    private int uniform(String name) { return uniforms.computeIfAbsent(name,key->GL20C.glGetUniformLocation(program,key)); }
    private void integer(String name,int value) { GL20C.glUniform1i(uniform(name),value); }
    private void scalar(String name,float value) { GL20C.glUniform1f(uniform(name),value); }
    private void rgb(String name,Vec3 value) { GL20C.glUniform3f(uniform(name),value.x(),value.y(),value.z()); }
    private void requireOpen() { RenderSystem.assertOnRenderThread();if(closed) throw new IllegalStateException("glTF program is closed"); }
    public void close() { RenderSystem.assertOnRenderThread();if(closed) return;if(bound) throw new IllegalStateException("glTF program is still bound");closed=true;GL20C.glDeleteProgram(program); }
    private static String read(String name) throws IOException {
        try(var input=GltfSurfaceProgram.class.getResourceAsStream("/assets/ysm/shaders/general_mesh/"+name)) {
            if(input==null) throw new IOException("Missing glTF shader: "+name);return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private static int compile(int type,String source) throws IOException {
        int shader=GL20C.glCreateShader(type);
        try { GL20C.glShaderSource(shader,source);GL20C.glCompileShader(shader);if(GL20C.glGetShaderi(shader,GL20C.GL_COMPILE_STATUS)==0) throw new IOException("Cannot compile glTF shader: "+GL20C.glGetShaderInfoLog(shader));return shader; }
        catch(Exception|Error failure) { GL20C.glDeleteShader(shader);throw failure; }
    }
}
