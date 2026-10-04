package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.mmd.*;
import com.elfmcys.ysm.client.renderer.MmdSurfaceProgram;
import com.elfmcys.ysm.client.renderer.MmdMeshRenderer;
import com.elfmcys.ysm.client.renderer.MmdRenderResources;
import com.elfmcys.ysm.client.renderer.SceneMeshBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.opengl.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real GL comparison with unmodified Saba shaders, plus separate source-feature/state checks. */
final class MmdMaterialGpuVerification {
    private static final String OWNER = "model.pmx";
    private static final FloatData ONE = new FloatData(1,1,1,1), ZERO = new FloatData(0,0,0,0);
    private static final FloatData DIFFUSE = new FloatData(.1f,.2f,.3f,.4f, .8f,.6f,.2f,.9f, .5f,.7f,.1f,.6f, .9f,.3f,.4f,.7f);
    private static final FloatData SPHERE = new FloatData(.6f,.4f,.8f,1, .8f,.4f,.2f,1, .3f,.7f,.9f,1, .1f,.5f,.3f,1);
    private static final FloatData TOON = new FloatData(.4f,.7f,.2f,1, .9f,.1f,.5f,1, .8f,.3f,.6f,1, .2f,.6f,.4f,1);
    static void verify() throws Exception {
        int reference = referenceProgram(), framebuffer = GL30C.glGenFramebuffers(), color = GL11C.glGenTextures();
        int oldSampler = 0;
        boolean samplers = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects;
        var textures = new ArrayList<AbstractTexture>();
        try (var shader = new MmdSurfaceProgram(false); var additional = new MmdSurfaceProgram(true);
             var mesh = new SceneMeshBuffer(); var referenceMesh = new SceneMeshBuffer()) {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, color);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA32F, 32, 32, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, 0L);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, color, 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("MMD framebuffer");
            GL11C.glViewport(0,0,32,32); GL11C.glDisable(GL11C.GL_BLEND); GL11C.glDisable(GL11C.GL_DEPTH_TEST); GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4); GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0); GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0); GL11C.glPixelStorei(GL11C.GL_UNPACK_SWAP_BYTES, 0);
            var sourceImages = new ScenePackageImages(Map.of(key(0), image(DIFFUSE), key(1), image(SPHERE), key(2), image(TOON)));
            var allBindings = new MmdTextureBindings(OWNER, profile(true,1,true));
            var prepared = PreparedSceneTextures.prepare(sourceImages, allBindings.requests(), ReadLimits.DEFAULT, () -> false);
            var ids = new HashMap<PreparedSceneTextures.Key,Integer>();
            var refIds = new HashMap<PreparedSceneTextures.Key,Integer>();
            for (var entry : prepared.textures().entrySet()) {
                var real = texture(entry.getValue()); textures.add(real); ids.put(entry.getKey(),real.getId());
                var referenceTexture = texture(flip(entry.getValue())); textures.add(referenceTexture); refIds.put(entry.getKey(),referenceTexture.getId());
            }
            mesh.upload(geometry(false), shader.layout(), ReadLimits.DEFAULT);
            referenceMesh.upload(geometry(true), Map.of("POSITION",0,"NORMAL",1,"TEXCOORD_0",2), ReadLimits.DEFAULT);
            var view = new MmdSurfaceProgram.View(Matrix4.IDENTITY, Matrix4.IDENTITY, new Vec3(.7f,.8f,.9f), new Vec3(1,0,0), ONE);
            if (samplers) { oldSampler = GL33C.glGenSamplers(); GL33C.glBindSampler(0,oldSampler); }
            for (int scenario=0; scenario<6; scenario++) {
                var profile=profile(scenario==1 || scenario==5,scenario==2 || scenario==5?1:scenario==3?2:0,scenario>=4);
                var values=morphed(profile.rest().materials().get(0).values());
                var material=profile.evaluate(List.of(values)).materials().get(0);
                var bindings=new MmdTextureBindings(OWNER,profile).materials().get(0);
                // Addition uses opaque alpha. Request lookup includes usage; create any new interpretation here.
                for(var entry:PreparedSceneTextures.prepare(sourceImages,new MmdTextureBindings(OWNER,profile).requests(),ReadLimits.DEFAULT,()->false).textures().entrySet()) {
                    if(!ids.containsKey(entry.getKey())) {
                        var a=texture(entry.getValue());textures.add(a);ids.put(entry.getKey(),a.getId());
                        var b=texture(flip(entry.getValue()));textures.add(b);refIds.put(entry.getKey(),b.getId());
                    }
                }
                GL20C.glUseProgram(reference); GL13C.glActiveTexture(GL13C.GL_TEXTURE0+7);
                try(var binding=shader.bind(material,bindings,ids::get,view)) { mesh.draw(); }
                if(GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)!=reference || GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)!=GL13C.GL_TEXTURE0+7)
                    throw new AssertionError("MMD program/unit state lost");
                if(samplers && GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING,0)!=oldSampler) throw new AssertionError("MMD sampler state lost");
                float[] actual=sample();
                if(samplers) GL33C.glBindSampler(0,0);
                referenceUniforms(reference,material,bindings,refIds,view);
                referenceMesh.draw(); float[] expected=sample();
                for(int component=0;component<4;component++) near(expected[component],actual[component],"Saba scenario "+scenario+" component "+component);
                if(samplers) GL33C.glBindSampler(0,oldSampler);
            }
            // Subtexture reads XY of the morphed additional UV, not view normals or the first UV set.
            var profile=profile(false,3,false);var bindings=new MmdTextureBindings(OWNER,profile);
            var subPixels=new FloatData(.6f,.4f,.8f,1, .8f,.4f,.2f,.6f, .3f,.7f,.9f,1, .1f,.5f,.3f,1);
            var subImages=new ScenePackageImages(Map.of(key(1),image(subPixels)));
            for(var entry:PreparedSceneTextures.prepare(subImages,bindings.requests(),ReadLimits.DEFAULT,()->false).textures().entrySet()) {
                var texture=texture(entry.getValue());textures.add(texture);ids.put(entry.getKey(),texture.getId());
            }
            var base=new MmdMorphState.Material(new FloatData(1,1,1,.5f),Vec3.ZERO,0,Vec3.ZERO,ONE,1,ONE,ZERO,ONE,ZERO,ONE,ZERO);
            var sub=profile.evaluate(List.of(base)).materials().get(0);
            mesh.upload(geometry(false),additional.layout(),ReadLimits.DEFAULT);
            var whiteView=new MmdSurfaceProgram.View(Matrix4.IDENTITY,Matrix4.IDENTITY,new Vec3(1,1,1),new Vec3(1,0,0),ONE);
            try(var binding=additional.bind(sub,bindings.materials().get(0),ids::get,whiteView)) { mesh.draw(); }
            var pixel=sample();near(.8f,pixel[0],"additional UV R");near(.4f,pixel[1],"additional UV G");near(.2f,pixel[2],"additional UV B");near(.3f,pixel[3],"subtexture opacity");
            int savedProgram=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), savedUnit=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            try {
                additional.bind(sub,bindings.materials().get(0),ignored->0,whiteView);
                throw new AssertionError("Missing texture was accepted");
            } catch(IllegalStateException expected) {
                if(!expected.getMessage().contains("not published")) throw expected;
            }
            if(savedProgram!=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM) || savedUnit!=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)) throw new AssertionError("Failed MMD bind changed host state");
            try(var binding=additional.bind(sub,bindings.materials().get(0),ids::get,whiteView)) { mesh.draw(); }
            near(.3f,sample()[3],"MMD bind retry");
            var shinyValues=new MmdMorphState.Material(new FloatData(0,0,0,1),new Vec3(1,1,1),8,Vec3.ZERO,ONE,1,ONE,ZERO,ONE,ZERO,ONE,ZERO);
            var shiny=profile(false,0,false).evaluate(List.of(shinyValues)).materials().get(0);
            var ortho=new MmdSurfaceProgram.View(Matrix4.IDENTITY,Matrix4.IDENTITY,new Vec3(1,1,1),new Vec3(0,0,1),ONE,true);
            mesh.upload(geometry(false),shader.layout(),ReadLimits.DEFAULT);
            try(var binding=shader.bind(shiny,new MmdTextureBindings.Material(null,null,null),ignored->0,ortho)) { mesh.draw(); }
            float highlight=(float)Math.pow(.8660254,8);
            near(highlight,pixel(8,8)[0],"orthographic parallel ray highlight left");
            near(highlight,pixel(24,24)[0],"orthographic parallel ray highlight right");
            verifyPasses(whiteView);
            if(GL11C.glGetError()!=GL11C.GL_NO_ERROR) throw new AssertionError("MMD material GL error");
            System.out.println("MMD surface: six unmodified Saba GLSL pixel comparisons, UV1 subtexture/alpha and program/texture-unit/sampler recovery passed");
        } finally {
            if(samplers) { GL33C.glBindSampler(0,0);if(oldSampler!=0) GL33C.glDeleteSamplers(oldSampler); }
            GL20C.glUseProgram(0); GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            for(var texture:textures) { texture.close();texture.releaseId(); }
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,0);GL30C.glDeleteFramebuffers(framebuffer);GL11C.glDeleteTextures(color);GL20C.glDeleteProgram(reference);
        }
    }
    private static void verifyPasses(MmdSurfaceProgram.View view) throws Exception {
        GL11C.glClearColor(0,0,0,0);
        GL11C.glDisable(GL11C.GL_BLEND); GL11C.glDisable(GL11C.GL_DEPTH_TEST); GL11C.glDepthFunc(GL11C.GL_GREATER); GL11C.glDepthMask(false);
        GL11C.glEnable(GL11C.GL_CULL_FACE); GL11C.glCullFace(GL11C.GL_FRONT); GL11C.glFrontFace(GL11C.GL_CW);
        // Forward-compatible GL 3.2 may reject widths greater than one even if a legacy range reports them.
        GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK,GL11C.GL_LINE); GL11C.glLineWidth(1);
        GL11C.glDisable(GL32C.GL_PROGRAM_POINT_SIZE); GL11C.glEnable(GL30C.GL_FRAMEBUFFER_SRGB); GL11C.glEnable(GL13C.GL_SAMPLE_ALPHA_TO_COVERAGE);
        GL14C.glBlendFuncSeparate(GL11C.GL_ONE,GL11C.GL_ZERO,GL11C.GL_ZERO,GL11C.GL_ONE);
        GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_REVERSE_SUBTRACT,GL14C.GL_FUNC_SUBTRACT);
        var edge=flagged(16,2);
        if(GL11C.glGetError()!=GL11C.GL_NO_ERROR) throw new AssertionError("Invalid test raster seed state");
        try(var resources=new MmdRenderResources(edge,new MmdTextureBindings(OWNER,edge));
            var renderer=new MmdMeshRenderer(resources,ReadLimits.DEFAULT)) {
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            renderer.render(List.of(passGeometry(true,1)),edge.rest(),view,ignored->0,false);
            near(.2f,pixel(25,16)[0],"MMD edge pixel width");near(0,pixel(27,16)[3],"MMD edge exterior");
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            renderer.render(List.of(passGeometry(true,0)),edge.rest(),view,ignored->0,false);
            near(0,pixel(25,16)[3],"MMD zero vertex edge scale");
        }
        var points=flagged(192,4);
        try(var resources=new MmdRenderResources(points,new MmdTextureBindings(OWNER,points));
            var renderer=new MmdMeshRenderer(resources,ReadLimits.DEFAULT)) {
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            renderer.render(List.of(passGeometry(false,1)),points.rest(),view,ignored->0,false);
            near(1,pixel(6,8)[3],"MMD point size");near(0,pixel(5,8)[3],"MMD point exterior");
            near(0,pixel(16,8)[3],"MMD point precedence over lines");
        }
        var lines=flagged(128,1);
        try(var resources=new MmdRenderResources(lines,new MmdTextureBindings(OWNER,lines));
            var renderer=new MmdMeshRenderer(resources,ReadLimits.DEFAULT)) {
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            renderer.render(List.of(passGeometry(false,1)),lines.rest(),view,ignored->0,false);
            if(!coveredNear(8,16) || !coveredNear(16,24) || !coveredNear(24,16) || !coveredNear(16,8)) throw new AssertionError("MMD wire lost an outer edge");
        }
        var vertexColor=flagged(32,1);
        try(var resources=new MmdRenderResources(vertexColor,new MmdTextureBindings(OWNER,vertexColor));
            var renderer=new MmdMeshRenderer(resources,ReadLimits.DEFAULT)) {
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            renderer.render(List.of(passGeometry(false,1)),vertexColor.rest(),view,ignored->0,false);
            var pixel=pixel(10,10);near(.45f,pixel[0],"MMD vertex color red");near(.15f,pixel[1],"MMD vertex color green");near(.24f,pixel[2],"MMD vertex color blue");near(.6f,pixel[3],"MMD vertex color alpha");
        }
        int[] polygonMode = new int[2];
        GL11C.glGetIntegerv(GL11C.GL_POLYGON_MODE, polygonMode);
        if(GL11C.glIsEnabled(GL11C.GL_BLEND) || GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST) || GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK)
                || GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC)!=GL11C.GL_GREATER || GL11C.glGetInteger(GL11C.GL_FRONT_FACE)!=GL11C.GL_CW
                || GL11C.glGetInteger(GL11C.GL_CULL_FACE_MODE)!=GL11C.GL_FRONT || polygonMode[0]!=GL11C.GL_LINE || polygonMode[1]!=GL11C.GL_LINE
                || GL11C.glGetFloat(GL11C.GL_LINE_WIDTH)!=1 || GL11C.glIsEnabled(GL32C.GL_PROGRAM_POINT_SIZE)
                || !GL11C.glIsEnabled(GL30C.GL_FRAMEBUFFER_SRGB) || !GL11C.glIsEnabled(GL13C.GL_SAMPLE_ALPHA_TO_COVERAGE)
                || GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB)!=GL11C.GL_ONE || GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA)!=GL11C.GL_ONE
                || GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB)!=GL14C.GL_FUNC_REVERSE_SUBTRACT)
            throw new AssertionError("MMD raster state was not restored");
        GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK,GL11C.GL_FILL); GL11C.glLineWidth(1); GL11C.glFrontFace(GL11C.GL_CCW);
        GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB); GL11C.glDisable(GL13C.GL_SAMPLE_ALPHA_TO_COVERAGE); GL11C.glDepthMask(true);
        System.out.println("MMD draw passes: per-vertex edge width, point/line precedence, complete wire edges, vertex RGBA, blend/depth/cull/raster restoration passed");
    }
    private static boolean coveredNear(int x,int y) {
        for(int dx=-1;dx<=1;dx++) for(int dy=-1;dy<=1;dy++) if(pixel(x+dx,y+dy)[3]>.9f) return true;
        return false;
    }
    private static MmdMaterials flagged(int flags,float edgeSize) {
        var names=new PmxDocument.Names("passes","");
        var material=new PmxDocument.Material(names,ONE,Vec3.ZERO,0,Vec3.ZERO,flags,new FloatData(.2f,.3f,.4f,1),edgeSize,-1,-1,0,false,-1,"",6);
        return YsmRuntime.scenes().materials(new PmxDocument(2.1f,new ByteData(new byte[]{1,1,4,4,4,4,4,4}),names,"","",List.of(),IntData.EMPTY,List.of(),List.of(material),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),ByteData.EMPTY));
    }
    private static MeshAsset.Primitive passGeometry(boolean reverse,float edgeScale) {
        var attributes=new HashMap<>(geometry(false).attributes());
        attributes.put("POSITION",new MeshAsset.Attribute(3,new FloatData(-.5f,-.5f,0,.5f,-.5f,0,-.5f,.5f,0,.5f,.5f,0)));
        attributes.put("NORMAL",new MeshAsset.Attribute(3,new FloatData(1,0,0,1,0,0,1,0,0,1,0,0)));
        attributes.put("_MMD_EDGE_SCALE",new MeshAsset.Attribute(1,new FloatData(edgeScale,edgeScale,edgeScale,edgeScale)));
        return new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,attributes,reverse?new IntData(0,2,1,2,3,1):new IntData(0,1,2,2,1,3),0,null,List.of());
    }
    private static MmdMaterials profile(boolean diffuse,int sphere,boolean toon) {
        var names=new PmxDocument.Names("material","");
        var material=new PmxDocument.Material(names,new FloatData(.4f,.3f,.2f,.8f),Vec3.ZERO,0,new Vec3(.1f,.2f,.3f),0,ONE,1,
                diffuse?0:-1,sphere==0?-1:1,sphere,false,toon?2:-1,"",6);
        var source=new PmxDocument(2.1f,new ByteData(new byte[]{1,1,4,4,4,4,4,4}),names,"","",List.of(),IntData.EMPTY,
                List.of("diffuse.png","sphere.png","toon.png"),List.of(material),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),ByteData.EMPTY);
        return YsmRuntime.scenes().materials(source);
    }
    private static MmdMorphState.Material morphed(MmdMorphState.Material value) {
        return new MmdMorphState.Material(value.diffuse(),value.specular(),value.shininess(),value.ambient(),value.edgeColor(),value.edgeSize(),
                new FloatData(.7f,.8f,.9f,.6f),new FloatData(.05f,.1f,.03f,.2f),new FloatData(.9f,.8f,.7f,.4f),new FloatData(.07f,.09f,.04f,.3f),new FloatData(.8f,.6f,.4f,.5f),new FloatData(.03f,.02f,.01f,.1f));
    }
    private static ScenePackageImages.Key key(int index) { return ScenePackageImages.Key.indexed(OWNER,index); }
    private static SceneImage image(FloatData data) { return new SceneImage(SceneImage.Format.PNG,2,2,data,new IntData(32,32,32,32),SceneImage.Alpha.STRAIGHT,Map.of(),ByteData.EMPTY); }
    private static PreparedSceneTextures.Pixels flip(PreparedSceneTextures.Pixels pixels) {
        float[] data=pixels.rgba().copy();for(int i=0;i<8;i++) { float a=data[i];data[i]=data[i+8];data[i+8]=a; }
        return new PreparedSceneTextures.Pixels(2,2,new FloatData(data),pixels.sampler());
    }
    static AbstractTexture texture(PreparedSceneTextures.Pixels pixels) throws Exception {
        var type=Class.forName(MinecraftSceneTextureHost.class.getName()+"$Texture");
        var constructor=type.getDeclaredConstructor(PreparedSceneTextures.Pixels.class);constructor.setAccessible(true);
        var texture=(AbstractTexture)constructor.newInstance(pixels);texture.load(null);
        // Keep the shader oracle's original float inputs independent of the host's
        // half-float storage policy. The production upload format has its own test.
        int previous=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D),pbo=GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] names={GL11C.GL_UNPACK_ALIGNMENT,GL11C.GL_UNPACK_ROW_LENGTH,GL11C.GL_UNPACK_SKIP_ROWS,GL11C.GL_UNPACK_SKIP_PIXELS,GL11C.GL_UNPACK_SWAP_BYTES};
        int[] saved=new int[names.length];for(int i=0;i<names.length;i++)saved[i]=GL11C.glGetInteger(names[i]);
        try {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER,0);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,texture.getId());
            for(int i=0;i<names.length;i++)GL11C.glPixelStorei(names[i],i==0?1:0);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL30C.GL_RGBA32F,pixels.width(),pixels.height(),0,GL11C.GL_RGBA,GL11C.GL_FLOAT,pixels.rgba().copy());
            if(pixels.sampler().minFilter()!=GL11C.GL_NEAREST&&pixels.sampler().minFilter()!=GL11C.GL_LINEAR)GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
        }finally{for(int i=0;i<names.length;i++)GL11C.glPixelStorei(names[i],saved[i]);GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER,pbo);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,previous);}
        return texture;
    }
    private static MeshAsset.Primitive geometry(boolean saba) {
        var positions=new MeshAsset.Attribute(3,new FloatData(-1,-1,-.5f,1,-1,-.5f,-1,1,-.5f,1,1,-.5f));
        var normals=new MeshAsset.Attribute(3,new FloatData(.3f,.4f,.8660254f,.3f,.4f,.8660254f,.3f,.4f,.8660254f,.3f,.4f,.8660254f));
        float v=saba?.35f:.65f;
        var uv=new MeshAsset.Attribute(2,new FloatData(.3f,v,.3f,v,.3f,v,.3f,v));
        var additional=new MeshAsset.Attribute(4,new FloatData(.75f,.25f,.4f,.6f,.75f,.25f,.4f,.6f,.75f,.25f,.4f,.6f,.75f,.25f,.4f,.6f));
        return new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,Map.of("POSITION",positions,"NORMAL",normals,"TEXCOORD_0",uv,"_MMD_UV1",additional,
                "_MMD_EDGE_SCALE",new MeshAsset.Attribute(1,new FloatData(1,1,1,1))),new IntData(0,1,2,2,1,3),0,null,List.of());
    }
    private static float[] sample() { return pixel(8,8); }
    private static float[] pixel(int x,int y) { float[] pixel=new float[4];GL11C.glReadPixels(x,y,1,1,GL11C.GL_RGBA,GL11C.GL_FLOAT,pixel);return pixel; }
    private static void near(float expected,float actual,String label) {
        if(!Float.isFinite(actual) || Math.abs(expected-actual)>2e-5) throw new AssertionError(label+": "+actual+" != "+expected);
    }
    private static int referenceProgram() throws Exception {
        int program=GL20C.glCreateProgram(),vertex=0,fragment=0;
        try {
            vertex=referenceShader("mmd.vert",GL20C.GL_VERTEX_SHADER);fragment=referenceShader("mmd.frag",GL20C.GL_FRAGMENT_SHADER);
            GL20C.glAttachShader(program,vertex);GL20C.glAttachShader(program,fragment);
            GL20C.glBindAttribLocation(program,0,"in_Pos");GL20C.glBindAttribLocation(program,1,"in_Nor");GL20C.glBindAttribLocation(program,2,"in_UV");
            GL20C.glLinkProgram(program);
            if(GL20C.glGetProgrami(program,GL20C.GL_LINK_STATUS)==0) throw new AssertionError(GL20C.glGetProgramInfoLog(program));
            return program;
        } catch(Exception|Error failure) { GL20C.glDeleteProgram(program);throw failure; }
        finally { if(vertex!=0) GL20C.glDeleteShader(vertex);if(fragment!=0) GL20C.glDeleteShader(fragment); }
    }
    private static int referenceShader(String file,int kind) throws Exception {
        String text;try(var in=MmdMaterialGpuVerification.class.getResourceAsStream("/mmd-material-oracle/"+file)) { text=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8); }
        int shader=GL20C.glCreateShader(kind);GL20C.glShaderSource(shader,text);GL20C.glCompileShader(shader);
        if(GL20C.glGetShaderi(shader,GL20C.GL_COMPILE_STATUS)==0) { String error=GL20C.glGetShaderInfoLog(shader);GL20C.glDeleteShader(shader);throw new AssertionError(error); }
        return shader;
    }
    private static void referenceUniforms(int program,MmdMaterials.Material material,MmdTextureBindings.Material textures,
                                          Map<PreparedSceneTextures.Key,Integer> ids,MmdSurfaceProgram.View view) {
        GL20C.glUseProgram(program);var value=material.values();
        GL20C.glUniformMatrix4fv(location(program,"u_WV"),false,Matrix4.IDENTITY.copy());GL20C.glUniformMatrix4fv(location(program,"u_WVP"),false,Matrix4.IDENTITY.copy());
        rgb(program,"u_Diffuse",new Vec3(value.diffuse().get(0),value.diffuse().get(1),value.diffuse().get(2)));
        rgb(program,"u_Ambient",value.ambient());rgb(program,"u_Specular",Vec3.ZERO);rgb(program,"u_LightColor",view.lightColor());rgb(program,"u_LightDir",new Vec3(-1,0,0));
        GL20C.glUniform1f(location(program,"u_Alpha"),value.diffuse().get(3));GL20C.glUniform1f(location(program,"u_SpecularPower"),0);
        GL20C.glUniform1i(location(program,"u_ShadowMapEnabled"),0);
        for(int i=0;i<4;i++) GL20C.glUniform1i(location(program,"u_ShadowMap"+i),3+i);
        GL20C.glUniform1i(location(program,"u_TexMode"),textures.diffuse()==null?0:2);
        GL20C.glUniform1i(location(program,"u_SphereTexMode"),textures.sphere()==null?0:material.definition().sphereMode().ordinal());
        GL20C.glUniform1i(location(program,"u_ToonTexMode"),textures.toon()==null?0:1);
        rgba(program,"u_TexMulFactor",value.textureMultiply());rgba(program,"u_TexAddFactor",value.textureAdd());
        rgba(program,"u_SphereTexMulFactor",value.sphereMultiply());rgba(program,"u_SphereTexAddFactor",value.sphereAdd());
        rgba(program,"u_ToonTexMulFactor",value.toonMultiply());rgba(program,"u_ToonTexAddFactor",value.toonAdd());
        var keys=Arrays.asList(textures.diffuse(),textures.sphere(),textures.toon());
        String[] names={"u_Tex","u_SphereTex","u_ToonTex"};
        for(int i=0;i<3;i++) { GL20C.glUniform1i(location(program,names[i]),i);GL13C.glActiveTexture(GL13C.GL_TEXTURE0+i);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,keys.get(i)==null?0:ids.get(keys.get(i))); }
    }
    private static int location(int program,String name) { return GL20C.glGetUniformLocation(program,name); }
    private static void rgb(int program,String name,Vec3 v) { GL20C.glUniform3f(location(program,name),v.x(),v.y(),v.z()); }
    private static void rgba(int program,String name,FloatData v) { GL20C.glUniform4f(location(program,name),v.get(0),v.get(1),v.get(2),v.get(3)); }
}
