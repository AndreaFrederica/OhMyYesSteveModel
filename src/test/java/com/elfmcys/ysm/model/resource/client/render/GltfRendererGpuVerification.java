package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.client.renderer.*;
import org.lwjgl.opengl.*;
import java.util.*;

/** Depth/color readback tests the draw owner, not only individual shader invocations. */
final class GltfRendererGpuVerification {
    private static final FloatData ONE = new FloatData(1,1,1,1);
    private static final GltfSurfaceProgram.View VIEW = new GltfSurfaceProgram.View(Matrix4.IDENTITY,
            new Matrix4(new float[]{1,0,0,0, 0,1,0,0, 0,0,-1,0, 0,0,0,1}),
            new Vec3(0,0,1), Vec3.ZERO, Vec3.ZERO, ONE);
    static void verify() throws Exception {
        int fbo = GL30C.glGenFramebuffers(), color = GL11C.glGenTextures(), depth = GL30C.glGenRenderbuffers();
        try {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,color);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL30C.GL_RGBA32F,32,32,0,GL11C.GL_RGBA,GL11C.GL_FLOAT,0L);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,fbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,color,0);
            GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER,depth);
            GL30C.glRenderbufferStorage(GL30C.GL_RENDERBUFFER,GL30C.GL_DEPTH_COMPONENT32F,32,32);
            GL30C.glFramebufferRenderbuffer(GL30C.GL_FRAMEBUFFER,GL30C.GL_DEPTH_ATTACHMENT,GL30C.GL_RENDERBUFFER,depth);
            if(GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)!=GL30C.GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Renderer framebuffer");
            GL11C.glViewport(0,0,32,32);
            var materials=List.of(material("BLEND",1,0,0,.5f),material("BLEND",0,1,0,.5f),material("OPAQUE",0,0,1,1));
            var initial=frame(draw(0,-.2f),draw(1,-.8f),draw(2,-.5f));
            try(var target=new GltfRenderResources("model",scene(materials),initial)) {
                var first=new GltfMeshRenderer(target,ReadLimits.DEFAULT);
                try(var second=new GltfMeshRenderer(target,ReadLimits.DEFAULT)) {
                    clear(); seedState(); var state=state();
                    first.render(frame(draw(1,-.2f),draw(0,-.8f),draw(2,-.5f)),materials,VIEW,ignored->0);
                    near(pixel(16),new float[]{0,.5f,.5f,1},"opaque occludes farther blend");
                    near(depth(),.75f,"blend preserves opaque depth");
                    if(!state.equals(state())) throw new AssertionError("Renderer raster state leaked");
                    clear();
                    first.render(frame(draw(0,-.2f),draw(1,-.8f)),materials,VIEW,ignored->0);
                    near(pixel(16),new float[]{.5f,.25f,0,.75f},"stable back to front blend");
                    near(depth(),1,"blend does not write depth");
                    first.close(); first.close();
                    clear();
                    var left=draw(2,transform(.4f,-.5f,-.4f),true);
                    var mirrored=draw(2,transform(-.4f,.5f,-.4f),true);
                    second.render(frame(left,mirrored),materials,VIEW,ignored->0);
                    near(pixel(8),new float[]{0,0,1,1},"node translation and scale");
                    near(pixel(24),new float[]{0,0,1,1},"negative determinant winding and target survives other instance");
                    clear();
                    var guiProjection=new Matrix4(1,0,0,0,0,-1,0,0,0,0,-1,0,0,0,0,1);
                    var guiView=new GltfSurfaceProgram.View(VIEW.modelView(),guiProjection,VIEW.toLight(),VIEW.lightRadiance(),VIEW.diffuseIrradiance(),VIEW.tint(),true);
                    second.render(frame(left,mirrored),materials,guiView,ignored->0);
                    near(pixel(8),new float[]{0,0,1,1},"GUI projection preserves front-face visibility");
                    near(pixel(24),new float[]{0,0,1,1},"GUI projection and mirrored node compose winding");
                    clear();
                    second.render(frame(draw(2,Matrix4.IDENTITY,false)),materials,VIEW,ignored->0);
                    near(pixel(16),new float[4],"hidden draw");
                    clear();
                    var invalid=new ArrayList<>(materials);
                    invalid.set(1,new SceneAsset.Material("","unlit",Map.of("baseColor",new FloatData(1)),Map.of(),"BLEND",.5f,false,Map.of()));
                    expectFailure(()->second.render(frame(draw(2,-.5f),draw(1,-.2f)),invalid,VIEW,ignored->0),"Invalid glTF material parameter");
                    near(pixel(16),new float[4],"late invalid material leaves framebuffer intact");
                    near(depth(),1,"late invalid material leaves depth intact");
                    // Variant publication includes initially hidden draws; changing visibility must not compile at draw time.
                    second.render(frame(draw(2,Matrix4.IDENTITY,true)),materials,VIEW,ignored->0);
                } finally { first.close(); }
            }
            try(var target=new GltfRenderResources("model",scene(materials),frame(draw(2,Matrix4.IDENTITY,false)))) {
                try(var renderer=new GltfMeshRenderer(target,ReadLimits.DEFAULT)) {
                    clear(); renderer.render(frame(draw(2,-.5f)),materials,VIEW,ignored->0);
                    near(pixel(16),new float[]{0,0,1,1},"initially hidden variant publication");
                    target.close();
                    expectFailure(()->renderer.render(frame(draw(2,-.5f)),materials,VIEW,ignored->0),"closed");
                }
            }
            texturePreflight(color);
            // Linear output needs exactly one transfer when the destination attachment is sRGB.
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,color);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL21C.GL_SRGB8_ALPHA8,32,32,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,0L);
            var gray=List.of(material("OPAQUE",.21404114f,.21404114f,.21404114f,1));
            try(var target=new GltfRenderResources("gray",scene(gray),frame(draw(0,-.5f)));var renderer=new GltfMeshRenderer(target,ReadLimits.DEFAULT)) {
                clear(); renderer.render(frame(draw(0,-.5f)),gray,VIEW,ignored->0);
                var encoded=java.nio.ByteBuffer.allocateDirect(4);GL11C.glReadPixels(16,16,1,1,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,encoded);
                for(int i=0;i<3;i++) if(Math.abs(Byte.toUnsignedInt(encoded.get(i))-128)>1) throw new AssertionError("sRGB framebuffer transfer");
            }
            if(GL11C.glGetError()!=GL11C.GL_NO_ERROR) throw new AssertionError("Renderer GL error");
            System.out.println("glTF renderer: opaque/blend depth, back-to-front sort, node transforms, mirrored winding, visibility, shared lifetime, frame preflight, raster restoration and sRGB destination passed");
        } finally {
            GL11C.glDepthMask(true);GL11C.glDisable(GL11C.GL_BLEND);GL11C.glDisable(GL11C.GL_CULL_FACE);GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK,GL11C.GL_FILL);
            GL11C.glDisable(GL11C.GL_POLYGON_OFFSET_FILL);GL11C.glDisable(GL13C.GL_SAMPLE_ALPHA_TO_COVERAGE);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,0);GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER,0);
            GL30C.glDeleteFramebuffers(fbo);GL30C.glDeleteRenderbuffers(depth);GL11C.glDeleteTextures(color);
        }
    }
    private static void texturePreflight(int textureId) throws Exception {
        var textureBinding=new SceneAsset.TextureBinding(0,0,new FloatData(1,0,0,0,1,0,0,0,1),1,Map.of());
        var textured=new SceneAsset.Material("","unlit",Map.of(),Map.of("baseColor",textureBinding),"OPAQUE",.5f,false,Map.of());
        var materials=List.of(material("OPAQUE",1,0,0,1),textured);
        var scene=scene(materials);
        scene=new SceneAsset(scene.name(),scene.coordinates(),scene.nodes(),scene.scenes(),scene.defaultScene(),scene.meshes(),scene.skins(),materials,
                List.of(new SceneAsset.Texture("",0,9728,9728,10497,10497,Map.of())),List.of(),List.of(),List.of(),List.of(),Map.of(),scene.compatibility());
        var frame=frame(draw(0,-.5f),draw(1,-.2f));
        try(var target=new GltfRenderResources("model",scene,frame);var renderer=new GltfMeshRenderer(target,ReadLimits.DEFAULT)) {
            clear();
            expectFailure(()->renderer.render(frame,materials,VIEW,ignored->0),"not published");
            near(pixel(16),new float[4],"late missing texture draws nothing");
            var changed=new SceneAsset.TextureBinding(1,0,textureBinding.uvTransform(),1,Map.of());
            var replacement=new SceneAsset.Material("","unlit",Map.of(),Map.of("baseColor",changed),"OPAQUE",.5f,false,Map.of());
            expectFailure(()->renderer.render(frame,List.of(materials.get(0),replacement),VIEW,ignored->textureId),"reference changed");
            near(pixel(16),new float[4],"changed texture reference cannot reuse old publication");
        }
    }
    private static SceneAsset.Material material(String alpha,float r,float g,float b,float a) {
        return new SceneAsset.Material("","unlit",Map.of("baseColor",new FloatData(r,g,b,a)),Map.of(),alpha,.5f,false,Map.of());
    }
    private static GeometryFrame.Draw draw(int material,float z) { return draw(material,transform(1,0,z),true); }
    private static GeometryFrame.Draw draw(int material,Matrix4 matrix,boolean visible) {
        var attributes=Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(-1,-1,0,1,-1,0,-1,1,0,1,1,0)),
                "TEXCOORD_0",new MeshAsset.Attribute(2,new FloatData(0,0,1,0,0,1,1,1)));
        var primitive=new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,attributes,new IntData(0,1,2,2,1,3),material,null,List.of());
        return new GeometryFrame.Draw(material,material,matrix,new MeshAsset("",List.of(primitive),FloatData.EMPTY),visible);
    }
    private static Matrix4 transform(float scaleX,float x,float z) { return new Matrix4(new float[]{scaleX,0,0,0,0,1,0,0,0,0,1,0,x,0,z,1}); }
    private static GeometryFrame frame(GeometryFrame.Draw... draws) { return new GeometryFrame(0,SceneAsset.Coordinates.GLTF,List.of(draws)); }
    private static SceneAsset scene(List<SceneAsset.Material> materials) {
        return new SceneAsset("",SceneAsset.Coordinates.GLTF,List.of(),List.of(),-1,List.of(),List.of(),materials,List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),new CompatibilityReport(List.of(),List.of()));
    }
    private static void clear() { GL11C.glDepthMask(true);GL11C.glClearColor(0,0,0,0);GL11C.glClearDepth(1);GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT); }
    private static float[] pixel(int x) { float[] p=new float[4];GL11C.glReadPixels(x,16,1,1,GL11C.GL_RGBA,GL11C.GL_FLOAT,p);return p; }
    private static float depth() { float[] p=new float[1];GL11C.glReadPixels(16,16,1,1,GL11C.GL_DEPTH_COMPONENT,GL11C.GL_FLOAT,p);return p[0]; }
    private static void near(float[] actual,float[] expected,String message) { for(int i=0;i<actual.length;i++) near(actual[i],expected[i],message+"["+i+"]"); }
    private static void near(float actual,float expected,String message) { if(!Float.isFinite(actual)||Math.abs(actual-expected)>3e-5) throw new AssertionError(message+": "+actual+" != "+expected); }
    private static void expectFailure(Runnable action,String message) {
        try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { if(expected.getMessage().contains(message)) return;throw expected; }
        throw new AssertionError("Accepted invalid frame: "+message);
    }
    private static void seedState() {
        GL11C.glDepthMask(false);GL11C.glDepthFunc(GL11C.GL_NEVER);GL11C.glFrontFace(GL11C.GL_CW);GL11C.glCullFace(GL11C.GL_FRONT);
        GL11C.glDisable(GL11C.GL_BLEND);GL11C.glDisable(GL11C.GL_DEPTH_TEST);GL11C.glEnable(GL11C.GL_CULL_FACE);
        GL11C.glEnable(GL11C.GL_POLYGON_OFFSET_FILL);GL11C.glEnable(GL13C.GL_SAMPLE_ALPHA_TO_COVERAGE);
        GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);GL11C.glDisable(GL32C.GL_PROGRAM_POINT_SIZE);
        GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK,GL11C.GL_LINE);
        GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_SUBTRACT,GL14C.GL_FUNC_REVERSE_SUBTRACT);
        GL14C.glBlendFuncSeparate(GL11C.GL_ONE,GL11C.GL_ZERO,GL11C.GL_ZERO,GL11C.GL_ONE);
    }
    private static List<Integer> state() {
        var values=new ArrayList<Integer>();
        for(int cap:new int[]{GL11C.GL_BLEND,GL11C.GL_CULL_FACE,GL11C.GL_DEPTH_TEST,GL32C.GL_PROGRAM_POINT_SIZE,GL30C.GL_FRAMEBUFFER_SRGB,
                GL13C.GL_SAMPLE_ALPHA_TO_COVERAGE,GL11C.GL_POLYGON_OFFSET_FILL,GL11C.GL_POLYGON_OFFSET_LINE,GL11C.GL_POLYGON_OFFSET_POINT}) values.add(GL11C.glIsEnabled(cap)?1:0);
        for(int key:new int[]{GL11C.GL_DEPTH_FUNC,GL11C.GL_DEPTH_WRITEMASK,GL11C.GL_FRONT_FACE,GL11C.GL_CULL_FACE_MODE,
                GL14C.GL_BLEND_SRC_RGB,GL14C.GL_BLEND_DST_RGB,GL14C.GL_BLEND_SRC_ALPHA,GL14C.GL_BLEND_DST_ALPHA,GL20C.GL_BLEND_EQUATION_RGB,GL20C.GL_BLEND_EQUATION_ALPHA,
                GL20C.GL_CURRENT_PROGRAM,GL13C.GL_ACTIVE_TEXTURE,GL30C.GL_VERTEX_ARRAY_BINDING,GL15C.GL_ARRAY_BUFFER_BINDING}) values.add(GL11C.glGetInteger(key));
        int[] polygon=new int[2];GL11C.glGetIntegerv(GL11C.GL_POLYGON_MODE,polygon);values.add(polygon[0]);values.add(polygon[1]);return values;
    }
}
