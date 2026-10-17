package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.client.renderer.*;
import com.elfmcys.ysm.model.resource.client.GeneralMeshModelResources;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import java.nio.file.*;
import java.util.*;

/** Production VRM CPU/GPU differential and lifecycle checks; hardware timings are not game FPS. */
public final class VrmOptimizationGpuVerification {
  static final ReadLimits LIMITS=new ReadLimits(1_000_000_000,100_000_000,4*1024*1024);
  public static void main(String[] args)throws Exception {
    var errors=GLFWErrorCallback.createPrint(System.err).set();long window=0;
    try{
      if(!GLFW.glfwInit())throw new IllegalStateException("GLFW failed");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
      window=GLFW.glfwCreateWindow(768,768,"VRM compute verification",0,0);if(window==0)throw new IllegalStateException("GL43 unavailable");GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
      System.out.println("GPU="+GL11C.glGetString(GL11C.GL_RENDERER));MmdComputeVerification.verify();synthetic();uploadBudget();
      for(String path:args)real(Path.of(path));
    }finally{if(window!=0)GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();GLFW.glfwSetErrorCallback(null);errors.free();}
  }
  static void synthetic()throws Exception {
    int n=513,influences=8;float[] p=new float[n*3],normal=new float[n*3],t=new float[n*4],weights=new float[n*influences];int[] offsets=new int[n+1],joints=new int[weights.length],indices=new int[n];var random=new Random(341);
    for(int v=0;v<n;v++){offsets[v]=v*influences;indices[v]=v;for(int k=0;k<3;k++){p[v*3+k]=random.nextFloat()*4-2;normal[v*3+k]=random.nextFloat()-.5f;t[v*4+k]=random.nextFloat();}t[v*4+3]=1;for(int k=0;k<influences;k++){joints[v*influences+k]=k;weights[v*influences+k]=1f/influences;}}offsets[n]=weights.length;
    var mesh=new MeshAsset.Primitive(MeshAsset.Topology.POINTS,Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(p)),"NORMAL",new MeshAsset.Attribute(3,new FloatData(normal)),"TANGENT",new MeshAsset.Attribute(4,new FloatData(t))),new IntData(indices),-1,
      new MeshAsset.Skinning(new IntData(offsets),new IntData(joints),new FloatData(weights),Collections.nCopies(n,MeshAsset.Deform.LINEAR),FloatData.EMPTY),List.of(new MeshAsset.MorphTarget("sparse",Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(.1f,.2f,.3f,.3f,-.2f,.1f))),new IntData(0,n-1))));
    try(var gpu=new MmdGpuDeformer(mesh,LIMITS,true)){
      for(int frame=0;frame<25;frame++){
        var palette=new ArrayList<Matrix4>();for(int j=0;j<influences;j++)palette.add(new Transform(new Vec3(frame*.01f,j*.2f,0),Rotation.axisAngle(new Vec3(1,2,3).normalized(),frame*.07+j*.05),new Vec3(-1.2f,2,.7f+j*.04f)).matrix());var morph=new FloatData(frame*.03f);
        if(!gpu.update(new Object(),palette,morph))throw new AssertionError("Compute rejected frame");var expected=YsmRuntime.scenes().deform(mesh,palette,morph,SceneProvider.NormalPolicy.INVERSE_TRANSPOSE);MmdComputeVerification.compare(expected,gpu.readback(),4e-5f);
        long count=gpu.dispatches();gpu.update(new Object(),palette,morph);if(gpu.dispatches()!=count)throw new AssertionError("Unchanged palette dispatched twice");
        var pos=expected.get("POSITION").values();float[] min={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY},max={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};for(int v=0;v<n;v++)for(int c=0;c<3;c++){min[c]=Math.min(min[c],pos.get(v*3+c));max[c]=Math.max(max[c],pos.get(v*3+c));}
        var center=gpu.boundsCenter();float[] a={center.x(),center.y(),center.z()};for(int c=0;c<3;c++)if(Math.abs(a[c]-(min[c]+max[c])*.5f)>4e-5)throw new AssertionError("GPU bounds mismatch");
      }
    }
    System.out.println("VRM GPU oracle: 513 vertices, eight influences, sparse morphs, reflected/nonuniform transforms, normals/tangents and bounds passed");
  }
  static void uploadBudget(){
    var attrs=Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(0,0,0,1,0,0,0,1,0)));
    var a=new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,attrs,new IntData(0,1,2),-1,null,List.of());
    var b=new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,attrs,new IntData(2,1,0),-1,null,List.of());
    var empty=new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,attrs,IntData.EMPTY,-1,null,List.of());
    try(var buffer=new SceneMeshBuffer(true)){
      var layout=Map.of("POSITION",0);var limit=new ReadLimits(100,100,1);
      buffer.upload(a,layout,limit);var before=buffer.uploadStatistics();
      try{buffer.upload(b,layout,limit);throw new AssertionError("Retained second view escaped budget");}catch(IllegalArgumentException expected){}
      if(!before.equals(buffer.uploadStatistics()))throw new AssertionError("Failed upload changed retained buffers");
      buffer.upload(b,layout,LIMITS);var pair=buffer.uploadStatistics();buffer.upload(a,layout,LIMITS);buffer.upload(b,layout,LIMITS);
      if(!pair.equals(buffer.uploadStatistics())||pair.vertexBytes()!=36||pair.indexBytes()!=24)throw new AssertionError("View buffer reuse failed");
      buffer.upload(empty,layout,LIMITS);buffer.draw();buffer.upload(a,layout,LIMITS);
    }
    if(GL11C.glGetError()!=GL11C.GL_NO_ERROR)throw new AssertionError("Zero-size/upload lifecycle failed");
  }
  static GeneralMeshInstance instance(GeneralMeshModelResources target,boolean gpu)throws Exception {System.setProperty("ysm.vrm.gpuSkinning",Boolean.toString(gpu));return new GeneralMeshInstance(target,ScenePackagePlayback.Selection.REST,new AnimationPreview.Range(0,30,60),ScenePackagePlayback.Settings.preview().withPhysics(true));}
  static void real(Path file)throws Exception {
    String name=file.getFileName().toString();var source=new ScenePackage(new ScenePackage.Source("model",name,ScenePackage.Format.VRM),new ScenePackage.Settings(1,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),List.of(),List.of(),Map.of(name,new ByteData(Files.readAllBytes(file))));
    try(var target=GeneralMeshModelResources.prepare(source,LIMITS,()->false);var host=new RealMeshGpuVerification.GlTextureHost()){
      target.publish(host,Integer::intValue,()->false);
      try(var cpu=instance(target,false);var gpu=instance(target,true)){
        for(boolean first:new boolean[]{false,true})if(!cpu.previewBounds(first).equals(gpu.previewBounds(first)))throw new AssertionError("Deferred preview bounds differ");
        var view=RealMeshGpuVerification.viewFor(cpu,false);
        for (int side=0;side<2;side++) {
          var camera=new GltfSurfaceProgram.View(view.modelView().multiply(new Transform(Vec3.ZERO,Rotation.axisAngle(new Vec3(0,1,0),side*Math.PI),new Vec3(1,1,1)).matrix()),view.projection(),view.toLight(),view.lightRadiance(),view.diffuseIrradiance(),view.tint(),true);
          GL11C.glViewport(0,0,768,768);GL11C.glClearColor(.32f,.4f,.55f,1);GL11C.glEnable(GL11C.GL_DEPTH_TEST);GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT);cpu.render(false,camera);
          float[] closeup=new float[768*768*4];GL11C.glReadPixels(0,0,768,768,GL11C.GL_RGBA,GL11C.GL_FLOAT,closeup);
          save(closeup,Path.of("build/reports/vrm-optimization",name+"-closeup-"+side+".png"));
        }
        int head=target.profile().bones().get("head").index();double maxRms=0;
        for(double time:new double[]{0,.1,.5,1,.25}){
          var rotation=Map.of(head,Rotation.axisAngle(new Vec3(0,1,0),time*.4));cpu.boneRotations(rotation);gpu.boneRotations(rotation);cpu.timeline().seek(time);gpu.timeline().seek(time);
          for(boolean first:new boolean[]{false,true}){var expected=pixels(cpu,view,first);var actual=pixels(gpu,view,first);double sum=0;int covered=0;for(int i=0;i<actual.length;i++){sum+=(actual[i]-expected[i])*(actual[i]-expected[i]);if(i%4!=3&&expected[i]>.05)covered++;}double rms=Math.sqrt(sum/actual.length);maxRms=Math.max(maxRms,rms);if(rms>.015)throw new AssertionError("CPU/GPU pixel difference "+name+" first="+first+" rms="+rms);if(!first&&covered<100)throw new AssertionError("Blank avatar image");if(time==.5)save(actual,Path.of("build/reports/vrm-optimization",name+(first?"-first":"-third")+".png"));}
        }
        if(gpu.vrmGpuDispatches()==0)throw new AssertionError("VRM compute was not selected");var uploads=gpu.vrmUploadStatistics();long dispatches=gpu.vrmGpuDispatches();gpu.render(false,view);gpu.render(true,view);if(!uploads.equals(gpu.vrmUploadStatistics())||gpu.vrmGpuDispatches()!=dispatches)throw new AssertionError("Repeated views uploaded or deformed again");
        cpu.timeline().seek(.75);gpu.timeline().seek(.75);var expected=pixels(cpu,view,false);GL15C.glBindBuffer(0xDEAD,0);var actual=pixels(gpu,view,false);double error=0;for(int i=0;i<actual.length;i++)error=Math.max(error,Math.abs(actual[i]-expected[i]));if(error>.015)throw new AssertionError("Same-pose fallback mismatch "+error);
        System.out.printf(Locale.ROOT,"VRM real model=%s version=%s maxPixelRms=%.7f dispatches=%d upload=%s fallbackError=%.7f%n",name,target.vrm().version(),maxRms,dispatches,uploads,error);
      }
      if(Boolean.getBoolean("ysm.vrm.benchmark"))benchmark(target,name);
    }
    if(GL11C.glGetError()!=GL11C.GL_NO_ERROR)throw new AssertionError("GL error after VRM resource release");
  }
  static void benchmark(GeneralMeshModelResources target,String name)throws Exception {
    var memory=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();memory.setThreadAllocatedMemoryEnabled(true);
    GltfSurfaceProgram.View view;try(var reference=instance(target,false)){view=RealMeshGpuVerification.viewFor(reference,false);}
    var rows=new ArrayList<String>();int head=target.profile().bones().get("head").index();int warmup=12,samples=40;
    for(boolean gpu:new boolean[]{false,true})for(int count:new int[]{1,4}){
      System.setProperty("ysm.vrm.gpuSkinning",Boolean.toString(gpu));var instances=new ArrayList<GeneralMeshInstance>();
      try{
        for(int i=0;i<count;i++)instances.add(GeneralMeshInstance.live(target));
        for(int i=0;i<warmup;i++)step(instances,view,head,i);
        long allocated=memory.getThreadAllocatedBytes(Thread.currentThread().getId()),vertices=instances.stream().mapToLong(p->p.vrmUploadStatistics().vertexBytes()).sum(),indices=instances.stream().mapToLong(p->p.vrmUploadStatistics().indexBytes()).sum(),frames=instances.stream().mapToLong(GeneralMeshInstance::vrmGpuUploadedBytes).sum();long[] times=new long[samples];
        for(int i=0;i<samples;i++){long start=System.nanoTime();step(instances,view,head,i+warmup);times[i]=System.nanoTime()-start;}
        allocated=memory.getThreadAllocatedBytes(Thread.currentThread().getId())-allocated;vertices=instances.stream().mapToLong(p->p.vrmUploadStatistics().vertexBytes()).sum()-vertices;indices=instances.stream().mapToLong(p->p.vrmUploadStatistics().indexBytes()).sum()-indices;frames=instances.stream().mapToLong(GeneralMeshInstance::vrmGpuUploadedBytes).sum()-frames;Arrays.sort(times);
        if(indices!=0)throw new AssertionError("Static view indices were uploaded again");
        var row=String.format(Locale.ROOT,"model=%s mode=%s instances=%d p50ms=%.4f p95ms=%.4f allocatedBytesPerFrame=%d vertexBytesPerFrame=%d indexBytesPerFrame=%d paletteBytesPerFrame=%d",name,gpu?"gpu":"cpu",count,times[samples/2]/1e6,times[(int)(samples*.95)]/1e6,allocated/samples,vertices/samples,indices/samples,frames/samples);rows.add(row);System.out.println(row);
      }finally{for(var instance:instances)instance.close();}
    }
    var path=Path.of("build/reports/vrm-optimization",name+"-benchmark.txt");Files.createDirectories(path.getParent());Files.write(path,rows);
  }
  static void step(List<GeneralMeshInstance> instances,GltfSurfaceProgram.View view,int head,int frame){for(var instance:instances){instance.boneRotations(Map.of(head,Rotation.axisAngle(new Vec3(0,1,0),Math.sin(frame*.07)*.5)));instance.sampleLive(frame,frame/60d);for(boolean first:new boolean[]{false,true}){GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT);instance.render(first,view);}}GL11C.glFinish();}
  static float[] pixels(GeneralMeshInstance instance,GltfSurfaceProgram.View view,boolean first){GL11C.glViewport(0,0,256,256);GL11C.glClearColor(0,0,0,1);GL11C.glEnable(GL11C.GL_DEPTH_TEST);GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT);instance.render(first,view);float[] pixels=new float[256*256*4];GL11C.glReadPixels(0,0,256,256,GL11C.GL_RGBA,GL11C.GL_FLOAT,pixels);return pixels;}
  static void save(float[] pixels,Path file)throws Exception{Files.createDirectories(file.getParent());int size=(int)Math.sqrt(pixels.length/4);var image=new java.awt.image.BufferedImage(size,size,java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<size;y++)for(int x=0;x<size;x++){int at=(y*size+x)*4,color=0;for(int c:new int[]{3,0,1,2})color=(color<<8)|Math.round(Math.max(0,Math.min(1,pixels[at+c]))*255);image.setRGB(x,size-1-y,color);}javax.imageio.ImageIO.write(image,"png",file.toFile());}
}


