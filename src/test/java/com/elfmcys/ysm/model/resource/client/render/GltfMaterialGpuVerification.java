package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.client.renderer.*;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.opengl.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Actual GL3.2 draw/readback. The reference BRDF source is pinned and unmodified. */
final class GltfMaterialGpuVerification {
    private static final FloatData ONE=new FloatData(1,1,1,1),UV_ID=new FloatData(1,0,0,0,1,0,0,0,1);
    private static final Vec3 LIGHT=new Vec3(.4f,.2f,1),RADIANCE=new Vec3(.7f,.8f,.9f);
    static void verify() throws Exception {
        int fbo=GL30C.glGenFramebuffers(),color=GL11C.glGenTextures(),reference=reference();
        var textures=new ArrayList<AbstractTexture>();
        try(var mesh=new SceneMeshBuffer();var referenceMesh=new SceneMeshBuffer()) {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,color);GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL30C.GL_RGBA32F,32,32,0,GL11C.GL_RGBA,GL11C.GL_FLOAT,0L);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,fbo);GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,color,0);
            if(GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)!=GL30C.GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("glTF framebuffer");
            GL11C.glViewport(0,0,32,32);GL11C.glDisable(GL11C.GL_BLEND);GL11C.glDisable(GL11C.GL_CULL_FACE);GL11C.glDisable(GL11C.GL_DEPTH_TEST);GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
            GL11C.glFrontFace(GL11C.GL_CCW);GL11C.glClearColor(0,0,0,0);
            var geometry=geometry();referenceMesh.upload(geometry,Map.of("POSITION",0),ReadLimits.DEFAULT);
            var view=new GltfSurfaceProgram.View(Matrix4.IDENTITY,Matrix4.IDENTITY,LIGHT,RADIANCE,Vec3.ZERO,ONE);
            for(float metallic:new float[]{0,.4f,1}) for(float roughness:new float[]{.02f,.3f,.8f}) {
                var material=material("metallic-roughness","OPAQUE",new FloatData(.8f,.3f,.2f,.4f),metallic,roughness,Map.of());
                try(var shader=new GltfSurfaceProgram(geometry,material)) {
                    mesh.upload(geometry,shader.layout(),ReadLimits.DEFAULT);
                    GL20C.glUseProgram(reference);GL13C.glActiveTexture(GL13C.GL_TEXTURE0+7);
                    try(var scope=shader.bind(material,new GltfTextureBindings("a",scene(material),material),ignored->0,view)) { mesh.draw(); }
                    float[] actual=pixel();
                    if(GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)!=reference || GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)!=GL13C.GL_TEXTURE0+7) throw new AssertionError("glTF shader state restoration");
                    referenceDraw(reference,referenceMesh,material,new Vec3(0,0,1));
                    compare(pixel(),actual,"Khronos GGX metallic="+metallic+" roughness="+roughness);
                }
            }
            var maps=new LinkedHashMap<String,SceneAsset.TextureBinding>();
            var orthoMaterial=material("metallic-roughness","OPAQUE",new FloatData(.8f,.3f,.2f,1),.4f,.3f,Map.of());
            try(var shader=new GltfSurfaceProgram(geometry,orthoMaterial)) {
                mesh.upload(geometry,shader.layout(),ReadLimits.DEFAULT);
                var orthoView=new GltfSurfaceProgram.View(Matrix4.IDENTITY,Matrix4.IDENTITY,LIGHT,RADIANCE,Vec3.ZERO,ONE,true);
                try(var scope=shader.bind(orthoMaterial,new GltfTextureBindings("a",scene(orthoMaterial),orthoMaterial),ignored->0,orthoView)) { mesh.draw(); }
                float[] actual=pixel();GL20C.glUseProgram(reference);GL20C.glUniform1i(GL20C.glGetUniformLocation(reference,"Orthographic"),1);
                referenceDraw(reference,referenceMesh,orthoMaterial,new Vec3(0,0,1));compare(pixel(),actual,"orthographic camera has parallel view rays");
                GL20C.glUniform1i(GL20C.glGetUniformLocation(reference,"Orthographic"),0);
            }
            maps.put("baseColor",new SceneAsset.TextureBinding(0,5,new FloatData(2,0,0,0,2,0,.55f,.05f,1),1,Map.of()));
            maps.put("metallicRoughness",new SceneAsset.TextureBinding(0,7,UV_ID,1,Map.of()));
            maps.put("normal",new SceneAsset.TextureBinding(0,2,UV_ID,1,Map.of()));
            maps.put("occlusion",new SceneAsset.TextureBinding(0,3,UV_ID,.5f,Map.of()));
            maps.put("emissive",new SceneAsset.TextureBinding(0,9,UV_ID,1,Map.of()));
            var combined=material("metallic-roughness","BLEND",new FloatData(.8f,.7f,.6f,.5f),.4f,.6f,maps);
            var bindings=new GltfTextureBindings("a",scene(combined),combined);
            var sourceImage=new SceneImage(SceneImage.Format.PNG,2,2,new FloatData(.5f,.5f,1,1, .5f,.25f,.75f,.4f, .9f,.6f,.3f,1, .2f,.3f,.4f,1),new IntData(32,32,32,32),SceneImage.Alpha.STRAIGHT,Map.of(),ByteData.EMPTY);
            var prepared=PreparedSceneTextures.prepare(new ScenePackageImages(Map.of(ScenePackageImages.Key.indexed("a",0),sourceImage)),bindings.requests(),ReadLimits.DEFAULT,()->false);
            var ids=new HashMap<PreparedSceneTextures.Key,Integer>();
            for(var entry:prepared.textures().entrySet()) { var texture=texture(entry.getValue());textures.add(texture);ids.put(entry.getKey(),texture.getId()); }
            try(var shader=new GltfSurfaceProgram(geometry,combined)) {
                mesh.upload(geometry,shader.layout(),ReadLimits.DEFAULT);
                try(var scope=shader.bind(combined,bindings,ids::get,view)) { mesh.draw(); }
                var actual=pixel();
                var baked=material("metallic-roughness","BLEND",new FloatData(srgb(.5f)*.8f,srgb(.25f)*.7f,srgb(.75f)*.6f,.2f),.12f,.36f,Map.of());
                referenceDraw(reference,referenceMesh,baked,new Vec3(0,0,1));compare(pixel(),actual,"five maps, TEXCOORD_2/3/5/7/9, UV transform, sRGB/data and alpha");
                var luminousParameters=new HashMap<>(combined.parameters());
                luminousParameters.put("emissive",new FloatData(.2f,.3f,.4f));luminousParameters.put("KHR_materials_emissive_strength/emissiveStrength",new FloatData(2));
                var luminous=new SceneAsset.Material("",combined.workflow(),luminousParameters,maps,"BLEND",.5f,true,
                        Map.of("extensions","{\"KHR_materials_emissive_strength\":{\"emissiveStrength\":2}}"));
                var ambientView=new GltfSurfaceProgram.View(Matrix4.IDENTITY,Matrix4.IDENTITY,LIGHT,Vec3.ZERO,new Vec3((float)Math.PI,(float)Math.PI,(float)Math.PI),ONE);
                try(var scope=shader.bind(luminous,bindings,ids::get,ambientView)) { mesh.draw(); }
                float[] illumination=new float[]{srgb(.5f),srgb(.25f),srgb(.75f),.2f};
                for(int c=0;c<3;c++) illumination[c]*=combined.parameters().get("baseColor").get(c)*.96f*.88f*.6f+luminousParameters.get("emissive").get(c)*2;
                compare(illumination,pixel(),"ambient occlusion, emissive sRGB and emissive strength");
                int unit=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE),old=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
                try { shader.bind(combined,bindings,ignored->0,view);throw new AssertionError("Unpublished texture accepted"); }
                catch(IllegalStateException expected) { if(!expected.getMessage().contains("not published")) throw expected; }
                if(unit!=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE) || old!=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)) throw new AssertionError("glTF failed bind restoration");
            }
            var mapped=material("metallic-roughness","OPAQUE",new FloatData(.8f,.3f,.2f,1),.4f,.6f,
                    Map.of("normal",new SceneAsset.TextureBinding(0,2,UV_ID,.4f,Map.of())));
            var normalTexture=texture(new PreparedSceneTextures.Pixels(1,1,new FloatData(.75f,.625f,1,1),new PreparedSceneTextures.Sampler(9728,9728,10497,10497)));
            textures.add(normalTexture);
            try(var shader=new GltfSurfaceProgram(geometry,mapped)) {
                mesh.upload(geometry,shader.layout(),ReadLimits.DEFAULT);
                var reflected=new Matrix4(new float[]{-1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1});
                var reflectedView=new GltfSurfaceProgram.View(reflected,Matrix4.IDENTITY,LIGHT,RADIANCE,Vec3.ZERO,ONE);
                GL11C.glFrontFace(GL11C.GL_CW);
                try(var scope=shader.bind(mapped,new GltfTextureBindings("a",scene(mapped),mapped),ignored->normalTexture.getId(),reflectedView)) { mesh.draw(); }
                float[] actual=pixel();GL11C.glFrontFace(GL11C.GL_CCW);
                float length=(float)Math.sqrt(1.05);referenceDraw(reference,referenceMesh,mapped,new Vec3(-.2f/length,.1f/length,1/length));
                compare(pixel(),actual,"normal scaling and reflected node tangent handedness");
            }
            for(String alpha:List.of("OPAQUE","MASK","BLEND")) {
                var unlit=material("unlit",alpha,new FloatData(.8f,.7f,.6f,.5f),0,1,Map.of("baseColor",maps.get("baseColor")));
                try(var shader=new GltfSurfaceProgram(geometry,unlit)) {
                    mesh.upload(geometry,shader.layout(),ReadLimits.DEFAULT);GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
                    try(var scope=shader.bind(unlit,new GltfTextureBindings("a",scene(unlit),unlit),ids::get,view)) { mesh.draw(); }
                    var expected=alpha.equals("MASK")?new float[4]:new float[]{srgb(.5f)*.8f,srgb(.25f)*.7f,srgb(.75f)*.6f,alpha.equals("OPAQUE")?1:.2f};
                    compare(expected,pixel(),"Unlit "+alpha);
                }
            }
            var coloredAttributes=new HashMap<>(geometry.attributes());coloredAttributes.put("COLOR_0",a(3,.2f,.4f,.6f,.2f,.4f,.6f,.2f,.4f,.6f,.2f,.4f,.6f));
            var colored=new MeshAsset.Primitive(geometry.topology(),coloredAttributes,geometry.indices(),0,null,List.of());
            var flat=material("unlit","BLEND",new FloatData(.5f,.5f,.5f,.3f),0,1,Map.of());
            try(var shader=new GltfSurfaceProgram(colored,flat)) {
                mesh.upload(colored,shader.layout(),ReadLimits.DEFAULT);
                try(var scope=shader.bind(flat,new GltfTextureBindings("a",scene(flat),flat),ignored->0,view)) { mesh.draw(); }
                compare(new float[]{.1f,.2f,.3f,.3f},pixel(),"VEC3 vertex color implicit alpha one");
            }
            if(GL11C.glGetError()!=GL11C.GL_NO_ERROR) throw new AssertionError("glTF GL error");
            System.out.println("glTF surface: nine pinned Khronos BRDF comparisons, five maps, UV2/3/5/7/9, sRGB/data, emissive/AO, reflected normal mapping, vertex RGB, alpha and binding recovery passed");
        } finally {
            GL20C.glUseProgram(0);GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            for(var texture:textures) { texture.close();texture.releaseId(); }
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,0);GL30C.glDeleteFramebuffers(fbo);GL11C.glDeleteTextures(color);GL20C.glDeleteProgram(reference);
        }
    }
    private static MeshAsset.Primitive geometry() {
        var attrs=new HashMap<String,MeshAsset.Attribute>();attrs.put("POSITION",a(3,-1,-1,-.5f,1,-1,-.5f,-1,1,-.5f,1,1,-.5f));
        attrs.put("NORMAL",a(3,0,0,1,0,0,1,0,0,1,0,0,1));attrs.put("TANGENT",a(4,1,0,0,1,1,0,0,1,1,0,0,1,1,0,0,1));
        for(int set:new int[]{2,3,5,7,9}) {
            float u=set==2 || set==7?.25f:set==5?.1f:.75f,v=set==7 || set==3?.75f:set==5?.1f:.25f;
            attrs.put("TEXCOORD_"+set,a(2,u,v,u,v,u,v,u,v));
        }
        return new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,attrs,new IntData(0,1,2,2,1,3),0,null,List.of());
    }
    private static SceneAsset.Material material(String workflow,String alpha,FloatData base,float metallic,float roughness,Map<String,SceneAsset.TextureBinding> textures) {
        return new SceneAsset.Material("",workflow,Map.of("baseColor",base,"metallic",new FloatData(metallic),"roughness",new FloatData(roughness)),textures,alpha,.5f,true,Map.of());
    }
    private static SceneAsset scene(SceneAsset.Material material) {
        return new SceneAsset("",SceneAsset.Coordinates.GLTF,List.of(),List.of(),-1,List.of(),List.of(),List.of(material),
                List.of(new SceneAsset.Texture("",0,9728,9728,10497,10497,Map.of())),List.of(),List.of(),List.of(),List.of(),Map.of(),new CompatibilityReport(List.of(),List.of()));
    }
    private static MeshAsset.Attribute a(int c,float... v) { return new MeshAsset.Attribute(c,new FloatData(v)); }
    private static float srgb(float x) { return (float)(x<=.04045?x/12.92:Math.pow((x+.055)/1.055,2.4)); }
    private static float[] pixel() { float[] p=new float[4];GL11C.glReadPixels(16,16,1,1,GL11C.GL_RGBA,GL11C.GL_FLOAT,p);return p; }
    private static void compare(float[] expected,float[] actual,String label) {
        for(int i=0;i<4;i++) if(!Float.isFinite(actual[i]) || Math.abs(expected[i]-actual[i])>3e-5) throw new AssertionError(label+" component "+i+": "+actual[i]+" != "+expected[i]);
    }
    private static AbstractTexture texture(PreparedSceneTextures.Pixels pixels) throws Exception {
        // The pinned BRDF oracle expects the original float samples. Share the MMD oracle's
        // RGBA32F fixture; production RGBA16F upload precision is tested separately.
        return MmdMaterialGpuVerification.texture(pixels);
    }
    private static int reference() throws Exception {
        String brdf;try(var input=GltfMaterialGpuVerification.class.getResourceAsStream("/gltf-material-oracle/brdf.glsl")) { brdf=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8); }
        int vertex=shader(GL20C.GL_VERTEX_SHADER,"#version 150\nin vec3 Position;out vec3 P;void main(){P=Position;gl_Position=vec4(Position,1);}"),fragment=0,program=GL20C.glCreateProgram();
        try {
            fragment=shader(GL20C.GL_FRAGMENT_SHADER,"#version 150\nconst float M_PI=3.141592653589793;\n"+brdf+"""
                \nin vec3 P;out vec4 Color;uniform vec4 Base;uniform float Metal;uniform float Rough;uniform vec3 Normal;uniform bool Orthographic;
                void main(){vec3 v=Orthographic?vec3(0,0,1):normalize(-P),l=normalize(vec3(.4,.2,1)),h=normalize(v+l);float nl=max(dot(Normal,l),0),nv=max(dot(Normal,v),0),nh=max(dot(Normal,h),0),vh=max(dot(v,h),0);
                vec3 spec=BRDF_specularGGX(Rough*Rough,nl,nv,nh);vec3 metal=F_Schlick(Base.rgb,vec3(1),vh)*spec;
                vec3 dielectric=mix(BRDF_lambertian(Base.rgb),spec,F_Schlick(.04,1.0,vh));
                Color=vec4(mix(dielectric,metal,Metal)*nl*vec3(.7,.8,.9),Base.a);}
                """);
            GL20C.glAttachShader(program,vertex);GL20C.glAttachShader(program,fragment);GL20C.glBindAttribLocation(program,0,"Position");GL20C.glLinkProgram(program);
            if(GL20C.glGetProgrami(program,GL20C.GL_LINK_STATUS)==0) throw new AssertionError(GL20C.glGetProgramInfoLog(program));return program;
        } catch(Exception|Error failure) { GL20C.glDeleteProgram(program);throw failure; }
        finally { GL20C.glDeleteShader(vertex);if(fragment!=0) GL20C.glDeleteShader(fragment); }
    }
    private static int shader(int type,String text) {
        int shader=GL20C.glCreateShader(type);GL20C.glShaderSource(shader,text);GL20C.glCompileShader(shader);
        if(GL20C.glGetShaderi(shader,GL20C.GL_COMPILE_STATUS)==0) { String error=GL20C.glGetShaderInfoLog(shader);GL20C.glDeleteShader(shader);throw new AssertionError(error); }return shader;
    }
    private static void referenceDraw(int program,SceneMeshBuffer mesh,SceneAsset.Material material,Vec3 normal) {
        GL20C.glUseProgram(program);var base=material.parameters().get("baseColor");
        GL20C.glUniform4f(GL20C.glGetUniformLocation(program,"Base"),base.get(0),base.get(1),base.get(2),material.alphaMode().equals("OPAQUE")?1:base.get(3));
        GL20C.glUniform1f(GL20C.glGetUniformLocation(program,"Metal"),material.parameters().get("metallic").get(0));
        GL20C.glUniform1f(GL20C.glGetUniformLocation(program,"Rough"),material.parameters().get("roughness").get(0));
        GL20C.glUniform3f(GL20C.glGetUniformLocation(program,"Normal"),normal.x(),normal.y(),normal.z());mesh.draw();
    }
}
