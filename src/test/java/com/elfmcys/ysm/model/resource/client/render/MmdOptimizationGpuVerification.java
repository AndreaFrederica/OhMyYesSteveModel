package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.java.JavaDeformationProvider;
import cc.sirrus.ysmlib.scene.mmd.*;
import com.elfmcys.ysm.client.renderer.*;
import com.elfmcys.ysm.model.resource.client.GeneralMeshModelResources;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;

/** Actual production owner, hidden hardware GL. Does not claim Minecraft-world FPS. */
public final class MmdOptimizationGpuVerification {
    private static final ReadLimits LIMITS=new ReadLimits(1_500_000_000,100_000_000,4*1024*1024);
    private static final com.sun.management.ThreadMXBean MEMORY=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static GeneralMeshModelResources prepare(ScenePackage source) throws java.io.IOException {
        if(!Boolean.getBoolean("ysm.scene.cacheVerification"))return GeneralMeshModelResources.prepare(source,LIMITS,()->false);
        var disk=new cc.sirrus.ysmlib.SceneDiskCache(Path.of("build/reports/mmd-cache-gpu"),"gpu-verification-1",4L*1024*1024*1024,1024L*1024*1024);
        try(var cold=GeneralMeshModelResources.prepare(source,LIMITS,()->false,disk,event->{})) {cold.assets();}
        var events=new ArrayList<cc.sirrus.ysmlib.SceneDiskCache.Event>();
        var result=GeneralMeshModelResources.prepare(source,LIMITS,()->false,disk,events::add);
        if(events.stream().anyMatch(e->Set.of("building","uncached").contains(e.state()))) {result.close();throw new AssertionError("GPU verification missed cache: "+events);}
        System.out.println("Production scene cache hit before GPU publication: "+source.model().path());return result;
    }
    public static void main(String[] args) throws Exception {
        var callback=GLFWErrorCallback.createPrint(System.err).set();long window=0;
        try {
            if(!GLFW.glfwInit())throw new IllegalStateException("GLFW failed");
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
            window=GLFW.glfwCreateWindow(256,256,"YSM MMD compute verification",0,0);if(window==0)throw new IllegalStateException("No GL43 context");
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            System.out.println("GL renderer="+GL11C.glGetString(GL11C.GL_RENDERER)+" version="+GL11C.glGetString(GL11C.GL_VERSION));
            MmdComputeVerification.verify();
            Path root=Path.of(System.getProperty("ysm.mmd.benchmark.corpus","D:/Projects/ysm/vrc-mmd"));List<Path> models,motions;
            try(var paths=Files.walk(root)){var all=paths.filter(Files::isRegularFile).sorted().toList();models=all.stream().filter(p->p.toString().endsWith(".pmx")).toList();motions=all.stream().filter(p->p.toString().endsWith(".vmd")).toList();}
            String extra=System.getProperty("ysm.mmd.benchmark.extraCorpus","");
            if(!extra.isBlank())try(var paths=Files.walk(Path.of(extra))){var combined=new ArrayList<>(models);combined.addAll(paths.filter(Files::isRegularFile).filter(p->FileSystems.getDefault().getPathMatcher("glob:"+System.getProperty("ysm.mmd.benchmark.extraModel","*")).matches(p.getFileName())).filter(p->p.toString().endsWith(".pmx")).sorted().toList());models=List.copyOf(combined);}
            Path motion=null;AnimationClip clip=null;
            for(var p:motions){var vmd=new VmdReader().read(new ByteData(Files.readAllBytes(p)),LIMITS);if(!vmd.bones().isEmpty()){motion=p;clip=new VmdAnimation().compile(vmd).clip();break;}}
            if(models.isEmpty()||motion==null)throw new IllegalArgumentException("No real MMD corpus");
            for(var path:models)verifyReal(path,motion,clip);
            System.out.println("MMD compute and host verification passed for "+models.size()+" real models");
            if(Boolean.getBoolean("ysm.mmd.benchmark.run")){
                var rows=new ArrayList<String>();rows.add("model,mode,physics,instances,samples,warmup,p50Ms,p95Ms,p99Ms,javaBytesPerFrame,vertexBytesPerFrame,gpuFrameBytesPerFrame,vertexUploads,indexUploads,stagingAllocations,gpuDispatches");
                for(var path:models)benchmark(path,motion,rows);
                Path output=Path.of("build/reports/mmd-optimization-gpu-benchmark.csv");Files.createDirectories(output.getParent());Files.write(output,rows);System.out.println("Host benchmark saved: "+output);
            }
        }finally{if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();GLFW.glfwSetErrorCallback(null);callback.free();}
    }
    private static GeneralMeshInstance instance(GeneralMeshModelResources target,boolean gpu,boolean reference,boolean physics)throws Exception {
        System.setProperty("ysm.mmd.gpuSkinning",Boolean.toString(gpu));System.setProperty("ysm.mmd.referenceUpload",Boolean.toString(reference));
        return new GeneralMeshInstance(target,new ScenePackagePlayback.Selection("motion",0),new AnimationPreview.Range(0,30,60),ScenePackagePlayback.Settings.preview().withPhysics(physics));
    }
    private static void verifyReal(Path path,Path motion,AnimationClip clip)throws Exception {
        var model=new PmxReader().read(new ByteData(Files.readAllBytes(path)),LIMITS);var mesh=new MmdMeshCompiler().compile(model).primitives().get(0);float worst=0;
        try(var player=new MmdPlayer(model,clip,cc.sirrus.ysmlib.YsmRuntime.physics(),MmdPlayback.Settings.preview(),Map.of());var gpu=new MmdGpuDeformer(mesh,LIMITS);var cpu=new JavaDeformationProvider().compile(mesh)){
            for(double time:new double[]{0,.1,.503,1,.25}){var frame=player.seek(time);var pose=frame.pose();
                if(!gpu.update(frame,pose.palette(),pose.morphs().meshWeights()))throw new AssertionError("Real GPU frame rejected");
                worst=Math.max(worst,MmdComputeVerification.compare(cpu.deform(pose.palette(),pose.morphs().meshWeights(),SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION),gpu.readback(),1e-4f));}
        }
        try(var target=prepare(RealMeshGpuVerification.buildPmxPackage(path,motion));var host=new RealMeshGpuVerification.GlTextureHost()){
            target.publish(host,Integer::intValue,()->false);
            try(var cpu=instance(target,false,false,true);var gpu=instance(target,true,false,true)){
                if(!((ScenePackagePlayback.Mmd)cpu.frame().details()).value().deformed()||((ScenePackagePlayback.Mmd)gpu.frame().details()).value().deformed())throw new AssertionError("Host deformation selection failed");
                var view=RealMeshGpuVerification.viewFor(cpu,false);cpu.timeline().seek(.5);gpu.timeline().seek(.5);
                var expected=pixels(cpu,view);var actual=pixels(gpu,view);double square=0;int different=0,covered=0;
                verifyGuiProjection(cpu,view,path.getFileName()+"-cpu");
                verifyGuiProjection(gpu,view,path.getFileName()+"-gpu");
                for(int i=0;i<expected.length;i+=4){float error=0;for(int k=0;k<4;k++){double d=expected[i+k]-actual[i+k];square+=d*d;error=Math.max(error,(float)Math.abs(d));}if(error>.02)different++;if(expected[i]!=0||expected[i+1]!=0||expected[i+2]!=0)covered++;}
                double rms=Math.sqrt(square/expected.length);if(covered<100||rms>.003||different>256*256*.005)throw new AssertionError("CPU/GPU pixel mismatch rms="+rms+" different="+different+" covered="+covered);
                long dispatch=gpu.mmdGpuDispatches();var logical=gpu.frame();gpu.render(false,view);gpu.render(true,view);
                if(gpu.frame()!=logical||gpu.mmdGpuDispatches()!=dispatch)throw new AssertionError("Two views changed frame or repeated compute");
                if(gpu.mmdUploadStatistics().vertexUploads()!=0||gpu.mmdUploadStatistics().indexUploads()!=model.materials().size())throw new AssertionError("GPU uploaded CPU geometry or repeated indices");
                var stats=cpu.mmdUploadStatistics();cpu.render(false,view);if(!stats.equals(cpu.mmdUploadStatistics()))throw new AssertionError("Same CPU frame uploaded twice");
                gpu.timeline().seek(.2);gpu.render(false,view);
                cpu.timeline().seek(.75);gpu.timeline().seek(.75);var fallbackExpected=pixels(cpu,view);
                // A device error must select CPU geometry from the existing logical pose.
                GL15C.glBindBuffer(0xDEAD,0);var fallbackActual=pixels(gpu,view);
                for(int i=0;i<fallbackExpected.length;i++)if(Math.abs(fallbackExpected[i]-fallbackActual[i])>.003)throw new AssertionError("Same-pose CPU fallback pixels changed");
                var fallbackStats=gpu.mmdUploadStatistics();var fallbackLogical=gpu.frame();gpu.render(false,view);gpu.render(true,view);
                if(fallbackStats.vertexUploads()!=1||!fallbackStats.equals(gpu.mmdUploadStatistics())||gpu.frame()!=fallbackLogical)throw new AssertionError("Fallback views repeated geometry upload or simulation");
                System.out.printf(Locale.ROOT,"Real compute: %s maxGeometryError=%.8f pixelRms=%.8f changedPixels=%d covered=%d%n",path.getFileName(),worst,rms,different,covered);
            }
            try(var gpu=instance(target,true,false,false)){gpu.select(ScenePackagePlayback.Selection.REST,new AnimationPreview.Range(0,1,60));gpu.render(false,RealMeshGpuVerification.viewFor(gpu,false));gpu.close();gpu.close();}
        }
        if(GL11C.glGetError()!=GL11C.GL_NO_ERROR)throw new AssertionError("Host lifecycle GL error");
    }
    private static float[] pixels(GeneralMeshInstance instance,GltfSurfaceProgram.View view){
        GL11C.glViewport(0,0,256,256);GL11C.glClearColor(0,0,0,1);GL11C.glEnable(GL11C.GL_DEPTH_TEST);GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT);
        instance.render(false,view);float[] pixels=new float[256*256*4];GL11C.glReadPixels(0,0,256,256,GL11C.GL_RGBA,GL11C.GL_FLOAT,pixels);return pixels;
    }
    private static void verifyGuiProjection(GeneralMeshInstance instance,GltfSurfaceProgram.View view,String name)throws Exception {
        var flip=new Matrix4(1,0,0,0,0,-1,0,0,0,0,1,0,0,0,0,1);
        var gui=new GltfSurfaceProgram.View(view.modelView(),flip.multiply(view.projection()),view.toLight(),view.lightRadiance(),view.diffuseIrradiance(),view.tint(),view.orthographic());
        var baseline=pixels(instance,view);var actual=pixels(instance,gui);float[] expected=new float[actual.length];
        double square=0;int different=0;
        for(int y=0;y<256;y++)for(int x=0;x<256;x++) {
            float error=0;
            for(int c=0;c<4;c++) {int i=(y*256+x)*4+c;expected[i]=baseline[((255-y)*256+x)*4+c];double delta=expected[i]-actual[i];square+=delta*delta;error=Math.max(error,(float)Math.abs(delta));}
            if(error>.02)different++;
        }
        var folder=Path.of("build/reports/mmd-gui-projection");Files.createDirectories(folder);
        savePixels(folder.resolve(name+"-expected.png"),expected);savePixels(folder.resolve(name+"-actual.png"),actual);
        double rms=Math.sqrt(square/actual.length);
        System.out.printf(Locale.ROOT,"GUI projection: %s pixelRms=%.8f changedPixels=%d%n",name,rms,different);
        if(rms>.003||different>256*256*.005)throw new AssertionError("GUI projection changed materials/front faces: "+name+" rms="+rms+" pixels="+different);
    }
    private static void savePixels(Path path,float[] pixels)throws Exception {
        var image=new java.awt.image.BufferedImage(256,256,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<256;y++)for(int x=0;x<256;x++){int i=(y*256+x)*4;int packed=0;
            for(int c:new int[]{3,0,1,2})packed=(packed<<8)|Math.round(Math.max(0,Math.min(1,pixels[i+c]))*255);
            image.setRGB(x,255-y,packed);}
        javax.imageio.ImageIO.write(image,"png",path.toFile());
    }
    private static double percentile(long[] values,double q){return values[Math.min(values.length-1,(int)Math.ceil(q*values.length)-1)]/1e6;}
    private static void benchmark(Path path,Path motion,List<String> rows)throws Exception {
        int samples=Integer.getInteger("ysm.mmd.benchmark.samples",90),warmup=30;if(samples<10||samples>2000)throw new IllegalArgumentException("Invalid sample count");
        try(var target=prepare(RealMeshGpuVerification.buildPmxPackage(path,motion));var host=new RealMeshGpuVerification.GlTextureHost()){
            target.publish(host,Integer::intValue,()->false);
            GltfSurfaceProgram.View fixedView;
            try(var reference=instance(target,false,false,false)){fixedView=RealMeshGpuVerification.viewFor(reference,false);}
            for(boolean physics:new boolean[]{false,true})for(String mode:List.of("cpu-reference-upload","cpu-reused-upload","gpu-compute"))for(int count:new int[]{1,4}){
                var instances=new ArrayList<GeneralMeshInstance>();
                try {
                    for(int i=0;i<count;i++)instances.add(instance(target,mode.equals("gpu-compute"),mode.equals("cpu-reference-upload"),physics));
                    var view=fixedView;
                    for(int i=1;i<=warmup;i++)draw(instances,view,i/60d);
                    long vertexBytes=instances.stream().mapToLong(p->p.mmdUploadStatistics().vertexBytes()).sum(),gpuBytes=instances.stream().mapToLong(GeneralMeshInstance::mmdGpuUploadedBytes).sum();
                    long allocations=MEMORY.getThreadAllocatedBytes(Thread.currentThread().getId());long[] times=new long[samples];
                    for(int i=0;i<samples;i++){long start=System.nanoTime();draw(instances,view,(warmup+i+1)/60d);times[i]=System.nanoTime()-start;}
                    allocations=MEMORY.getThreadAllocatedBytes(Thread.currentThread().getId())-allocations;Arrays.sort(times);
                    long uploads=0,indices=0,staging=0,dispatches=0;
                    for(var p:instances){var s=p.mmdUploadStatistics();vertexBytes-=s.vertexBytes();gpuBytes-=p.mmdGpuUploadedBytes();uploads+=s.vertexUploads();indices+=s.indexUploads();staging+=s.stagingAllocations();dispatches+=p.mmdGpuDispatches();}
                    String row=String.format(Locale.ROOT,"\"%s\",%s,%s,%d,%d,%d,%.4f,%.4f,%.4f,%.1f,%.1f,%.1f,%d,%d,%d,%d",path.getFileName(),mode,physics,count,samples,warmup,percentile(times,.5),percentile(times,.95),percentile(times,.99),allocations/(double)samples,-vertexBytes/(double)samples,-gpuBytes/(double)samples,uploads,indices,staging,dispatches);
                    rows.add(row);System.out.println(row);
                    if(mode.equals("gpu-compute")&&dispatches!=(samples+warmup)*(long)count)throw new AssertionError("Compute count mismatch");
                    if(!mode.equals("cpu-reference-upload")&&!mode.equals("gpu-compute")&&staging!=count)throw new AssertionError("Staging not reused");
                }finally{for(var p:instances)p.close();}
            }
        }
    }
    private static void draw(List<GeneralMeshInstance> instances,GltfSurfaceProgram.View view,double seconds){
        for(var p:instances){p.timeline().seek(seconds);
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT);p.render(false,view);
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT);p.render(true,view);}GL11C.glFinish();
    }
}
