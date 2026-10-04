package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.fbx.FbxEvaluation;
import com.elfmcys.ysm.client.renderer.*;
import com.elfmcys.ysm.model.resource.client.GeneralMeshModelResources;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.opengl.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses real Lib loading/playback and real GL shaders/textures through the target publication transaction. */
final class GeneralMeshOwnerGpuVerification {
    static void verify() throws Exception {
        var cancelled=new AtomicBoolean();
        // Preparation must succeed on a worker with no current GL context.
        var prepared=CompletableFuture.supplyAsync(()->{
            try { return GeneralMeshModelResources.prepare(source(),ReadLimits.DEFAULT,()->false); }
            catch(IOException error) { throw new CompletionException(error); }
        }).get();
        var host=new Host();host.failAt=2;
        try(prepared) {
            try { prepared.publish(host,Integer::intValue,()->false);throw new AssertionError("Upload failure published"); }
            catch(IOException expected) { if(!expected.getMessage().equals("injected upload failure")) throw expected; }
            if(host.live.size()!=0 || host.releases!=1) throw new AssertionError("Failed target retained partial texture ownership");
            expectClosed(prepared);
            host.failAt=0;host.cancel=cancelled;
            try { prepared.publish(host,Integer::intValue,cancelled::get);throw new AssertionError("Cancellation published"); }
            catch(CancellationException expected) { }
            if(!host.live.isEmpty() || host.releases!=2) throw new AssertionError("Cancelled target retained textures");
            cancelled.set(false);host.cancel=null;
            prepared.publish(host,Integer::intValue,()->false);
            if(host.live.size()!=2) throw new AssertionError("Sampler variants not independently published");
            var range=new AnimationPreview.Range(0,1,30);
            var selection=new ScenePackagePlayback.Selection("model",0);
            var first=new GeneralMeshInstance(prepared,selection,range,ScenePackagePlayback.Settings.preview());
            try(var second=new GeneralMeshInstance(prepared,selection,range,ScenePackagePlayback.Settings.preview())) {
                first.timeline().play();first.advance(1,.5);
                var half=first.frame();
                if(half.seconds()!=.5 || first.advance(1,.5)!=half || second.frame().seconds()!=0)
                    throw new AssertionError("Independent session or same-sequence read changed time");
                try { first.select(new ScenePackagePlayback.Selection("missing",0),range);throw new AssertionError("Missing clip accepted"); }
                catch(IllegalArgumentException expected) { }
                if(first.frame()!=half || !first.selection().equals(selection)) throw new AssertionError("Failed selection destroyed current session");
                drawBothViews(first);
                if(first.frame()!=half) throw new AssertionError("Drawing advanced animation");
                first.close();first.close();
                if(host.live.size()!=2) throw new AssertionError("Instance closed shared textures");
                second.timeline().seek(.8);second.timeline().seek(.2);drawBothViews(second);
                if(second.frame().seconds()!=.2) throw new AssertionError("Seek/replay time lost");
                second.select(ScenePackagePlayback.Selection.REST,range);drawBothViews(second);
                SceneHostGpuVerification.verify(second);
            } finally { first.close(); }
            prepared.close();prepared.close();
            if(!host.live.isEmpty() || host.releases!=4) throw new AssertionError("Target close did not release textures exactly once");
            expectClosed(prepared);
        }
        if(GL11C.glGetError()!=GL11C.GL_NO_ERROR) throw new AssertionError("Scene owner GL error");
        System.out.println("Scene target: worker Lib preparation, shader/texture transaction rollback, cancellation/retry, independent playback, same-frame views, failed-selection recovery and exactly-once shared release passed");
    }
    private static void drawBothViews(GeneralMeshInstance instance) {
        var view=new GltfSurfaceProgram.View(Matrix4.IDENTITY,Matrix4.IDENTITY,new Vec3(0,0,1),new Vec3(1,1,1),Vec3.ZERO,new FloatData(1,1,1,1));
        instance.render(false,view);instance.render(true,view);
    }
    private static void expectClosed(GeneralMeshModelResources target) {
        try { target.requirePublished();throw new AssertionError("Unpublished target exposed"); }
        catch(IllegalStateException expected) { }
    }
    private static ScenePackage source() throws IOException {
        var buffer=ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
        for(float value:new float[]{-.5f,-.5f,0,.5f,-.5f,0,-.5f,.5f,0,.5f,.5f,0, 0,0,1,0,0,1,1,1, 0,1, 0,0,-.5f,.25f,0,-.5f}) buffer.putFloat(value);
        var png=new ByteArrayOutputStream();var image=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,0xffffffff);javax.imageio.ImageIO.write(image,"png",png);
        var text="""
                {"asset":{"version":"2.0"},"extensionsUsed":["KHR_materials_unlit"],
                 "buffers":[{"uri":"mesh.bin","byteLength":112}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":48},{"buffer":0,"byteOffset":48,"byteLength":32},
                                {"buffer":0,"byteOffset":80,"byteLength":8},{"buffer":0,"byteOffset":88,"byteLength":24}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3","min":[-0.5,-0.5,0],"max":[0.5,0.5,0]},
                              {"bufferView":1,"componentType":5126,"count":4,"type":"VEC2"},
                              {"bufferView":2,"componentType":5126,"count":2,"type":"SCALAR"},
                              {"bufferView":3,"componentType":5126,"count":2,"type":"VEC3"}],
                 "images":[{"uri":"white.png"}],"samplers":[{"minFilter":9728,"magFilter":9728},{"minFilter":9729,"magFilter":9729}],
                 "textures":[{"source":0,"sampler":0},{"source":0,"sampler":1}],
                 "materials":[{"extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}},
                              {"extensions":{"KHR_materials_unlit":{}},"pbrMetallicRoughness":{"baseColorTexture":{"index":1}}}],
                 "meshes":[{"primitives":[{"mode":5,"attributes":{"POSITION":0,"TEXCOORD_0":1},"material":0},
                                           {"mode":5,"attributes":{"POSITION":0,"TEXCOORD_0":1},"material":1}]}],
                 "nodes":[{"mesh":0,"translation":[0,0,-0.5]}],"scenes":[{"nodes":[0]}],"scene":0,
                 "animations":[{"name":"move","samplers":[{"input":2,"output":3}],"channels":[{"sampler":0,"target":{"node":0,"path":"translation"}}]}]}
                """;
        return new ScenePackage(new ScenePackage.Source("model","avatar.gltf",ScenePackage.Format.GLTF),
                new ScenePackage.Settings(1,0,FbxEvaluation.SkinSpace.BIND_WORLD),List.of(),List.of(),
                Map.of("avatar.gltf",new ByteData(text.getBytes(StandardCharsets.UTF_8)),"mesh.bin",new ByteData(buffer.array()),"white.png",new ByteData(png.toByteArray())));
    }
    private static final class Host implements SceneTexturePublication.Host<Integer> {
        private final Map<Integer,AbstractTexture> live=new HashMap<>();
        private int attempts,releases,failAt;
        private AtomicBoolean cancel;
        @Override public Integer register(PreparedSceneTextures.Pixels pixels) throws Exception {
            if(++attempts==failAt) throw new IOException("injected upload failure");
            var type=Class.forName(MinecraftSceneTextureHost.class.getName()+"$Texture");
            var constructor=type.getDeclaredConstructor(PreparedSceneTextures.Pixels.class);constructor.setAccessible(true);
            var value=(AbstractTexture)constructor.newInstance(pixels);
            try { value.load(null);live.put(value.getId(),value); }
            catch(Exception|Error failure) { value.close();value.releaseId();throw failure; }
            if(cancel!=null) cancel.set(true);return value.getId();
        }
        @Override public void release(Integer id) {
            var value=live.remove(id);if(value==null) throw new AssertionError("Texture released twice");
            releases++;value.close();value.releaseId();
        }
    }
}
