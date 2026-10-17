package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.vrm.VrmMaterials;
import com.elfmcys.ysm.client.renderer.*;
import org.lwjgl.opengl.*;
import java.util.*;

/** Analytic raster checks: independent expected edges/colors, not CPU/GPU self-comparison. */
final class VrmSurfacePixelVerification {
    private static final CompatibilityReport REPORT = new CompatibilityReport(List.of(), List.of());
    static void verify() throws Exception {
        int old = GL11C.glGetInteger(GL30C.GL_FRAMEBUFFER_BINDING), front = GL11C.glGetInteger(GL11C.GL_FRONT_FACE);
        int[] viewport = new int[4]; GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        int fbo = GL30C.glGenFramebuffers(), color = GL11C.glGenTextures(), mask = GL11C.glGenTextures();
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, color);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA32F, 128, 128, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, (java.nio.ByteBuffer)null);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, color, 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("VRM pixel FBO incomplete");
            GL11C.glViewport(0, 0, 128, 128); GL11C.glFrontFace(GL11C.GL_CCW);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST); GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_BLEND); GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
            // Deliberately different R/G: legacy uses R, MToon 1 uses G.
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, mask);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA32F, 1, 1, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, new float[]{0, .5f, 1, 1});
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            for (boolean legacy : new boolean[]{true, false}) {
                // Quad ends at x=0, its +X normals extrude by 0.25 world units (16 pixels).
                edge(legacy, false, mask, "worldCoordinates", Matrix4.IDENTITY, 79);
                // Zero legacy R suppresses displacement but must preserve alpha. G=.5 halves modern width.
                edge(legacy, true, mask, "worldCoordinates", Matrix4.IDENTITY, legacy ? 63 : 71);
                edge(legacy, false, mask, "worldCoordinates", new Transform(Vec3.ZERO, Rotation.IDENTITY, new Vec3(2,2,2)).matrix(), 79);
                // 2:1 perspective at distance 2; legacy max-distance=1, modern scales with distance.
                edge(legacy, false, mask, "screenCoordinates", new Transform(new Vec3(0,0,-2),Rotation.IDENTITY,new Vec3(1,1,1)).matrix(), legacy ? 67 : 71);
            }
            System.out.println("VRM pixels: legacy 0.01 outline units, modern metre units, R/G vertex masks, opaque mask alpha, scaled transforms, screen aspect/distance and independent lit/shade albedos passed");
        } finally {
            GL11C.glFrontFace(front); GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, old); GL11C.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            GL30C.glDeleteFramebuffers(fbo); GL11C.glDeleteTextures(color); GL11C.glDeleteTextures(mask);
        }
    }
    private static void edge(boolean legacy, boolean textured, int textureId, String mode, Matrix4 transform, int right) throws Exception {
        var projection = mode.equals("screenCoordinates") ? new Matrix4(.5f,0,0,0, 0,1,0,0, 0,0,0,-1, 0,0,0,0) : Matrix4.IDENTITY;
        var parameters = new HashMap<String,FloatData>();
        parameters.put(legacy ? "_OutlineWidth" : "VRMC_materials_mtoon/outlineWidthFactor", new FloatData(legacy ? 25 : .25f));
        parameters.put(legacy ? "_OutlineColor" : "VRMC_materials_mtoon/outlineColorFactor", new FloatData(1,1,1));
        parameters.put(legacy ? "_OutlineLightingMix" : "VRMC_materials_mtoon/outlineLightingMixFactor", new FloatData(0));
        parameters.put(legacy ? "_Color" : "baseColor", new FloatData(.8f,.6f,.4f,1));
        parameters.put(legacy ? "_ShadeColor" : "VRMC_materials_mtoon/shadeColorFactor", new FloatData(.2f,.3f,.4f));
        String slot = legacy ? "_OutlineWidthTexture" : "VRMC_materials_mtoon/outlineWidthMultiplyTexture";
        var binding = new SceneAsset.TextureBinding(0,0,new FloatData(1,0,0,0,1,0,0,0,1),1,Map.of());
        var textures = new HashMap<String,VrmMaterials.Texture>();
        if (textured) {
            textures.put(slot,new VrmMaterials.Texture(binding,VrmMaterials.Transfer.LINEAR,legacy?0:1,VrmMaterials.UvRule.STATIC));
            textures.put(legacy ? "_ShadeTexture" : "VRMC_materials_mtoon/shadeMultiplyTexture",new VrmMaterials.Texture(binding,VrmMaterials.Transfer.LINEAR,-1,VrmMaterials.UvRule.STATIC));
        }
        var material = new VrmMaterials.Material(0,"MToon",legacy?VrmMaterials.Shader.MTOON_0:VrmMaterials.Shader.MTOON_1,
                legacy?VrmMaterials.ParameterSpace.LEGACY_SHADER:VrmMaterials.ParameterSpace.GLTF_LINEAR,parameters,textures,Map.of(),
                new VrmMaterials.RenderState("OPAQUE",.5f,true,VrmMaterials.Cull.BACK,1,0,false,0,0,-1,mode,VrmMaterials.Cull.FRONT),0,REPORT);
        var scene = new SceneAsset("test", SceneAsset.Coordinates.GLTF,List.of(),List.of(),-1,List.of(),List.of(),List.of(),
                List.of(new SceneAsset.Texture("mask",0,9728,9728,33071,33071,Map.of())),List.of(),List.of(),List.of(),List.of(),Map.of(),REPORT);
        var geometry = new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,Map.of(
                "POSITION",new MeshAsset.Attribute(3,new FloatData(-.5f,-.5f,0, 0,-.5f,0, 0,.5f,0, -.5f,.5f,0)),
                "NORMAL",new MeshAsset.Attribute(3,new FloatData(1,0,0, 1,0,0, 1,0,0, 1,0,0)),
                "TEXCOORD_0",new MeshAsset.Attribute(2,new FloatData(0,0, 1,0, 1,1, 0,1))),new IntData(0,1,2,0,2,3),0,null,List.of());
        GL11C.glClearColor(0,0,0,0); GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
        try (var program = new VrmSurfaceProgram(geometry,material); var mesh = new SceneMeshBuffer()) {
            mesh.upload(geometry,program.layout(),ReadLimits.DEFAULT);
            var view = new VrmSurfaceProgram.View(transform,projection,new Vec3(1,0,0),new Vec3(1,1,1),Vec3.ZERO,new FloatData(1,1,1,1),true);
            try (var bound = program.prepare(material,new VrmTextureBindings("test",scene,material),ignored->textureId,view).bind(true)) { mesh.draw(); }
            float[] row = new float[128*4]; GL11C.glReadPixels(0,64,128,1,GL11C.GL_RGBA,GL11C.GL_FLOAT,row);
            if (Math.abs(row[right*4+3]-1)>.001 || row[(right+1)*4+3]>.001)
                throw new AssertionError("Wrong outline edge/alpha: legacy="+legacy+" mask="+textured+" right="+right+" inside="+row[right*4+3]+" outside="+row[(right+1)*4+3]);
            for (int direction : new int[]{1,-1}) {
                var shadingView = new VrmSurfaceProgram.View(transform,projection,new Vec3(direction,0,0),new Vec3(1,1,1),Vec3.ZERO,new FloatData(1,1,1,1),true);
                GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
                try (var bound = program.prepare(material,new VrmTextureBindings("test",scene,material),ignored->textureId,shadingView).bind()) { mesh.draw(); }
                float[] pixel = new float[4]; GL11C.glReadPixels(60,64,1,1,GL11C.GL_RGBA,GL11C.GL_FLOAT,pixel);
                float[] expected = direction == 1 ? new float[]{.8f,.6f,.4f} : textured ? new float[]{0,.15f,.4f} : new float[]{.2f,.3f,.4f};
                for (int channel=0;channel<3;channel++) if (Math.abs(pixel[channel]-expected[channel])>.001)
                    throw new AssertionError("MToon lit/shade albedo mismatch legacy="+legacy+" textured="+textured+" light="+direction+" channel="+channel+" actual="+pixel[channel]);
            }
        }
    }
}
