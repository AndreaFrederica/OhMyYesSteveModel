package cc.sirrus.ysmlib.scene.physics.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import cc.sirrus.ysmlib.scene.physics.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Opt-in local corpus validation; copyrighted models are read in place, never bundled. */
public final class NativePhysicsCorpus {
  static String quote(Object text){return "\""+text.toString().replace("\"","\"\"")+"\"";}
  static String hash(ByteData data) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.copy()));}
  static PhysicsProvider traced(PhysicsProvider provider,List<List<PhysicsSpec.BodyState>> states) {
    return new PhysicsProvider() {
      public String id(){return provider.id();}
      public PhysicsWorld createWorld(PhysicsSpec.World settings) {
        var world=provider.createWorld(settings);
        return (PhysicsWorld)Proxy.newProxyInstance(PhysicsWorld.class.getClassLoader(),new Class<?>[]{PhysicsWorld.class},(proxy,method,args)->{
          try {var result=method.invoke(world,args);if(method.getName().equals("step"))states.add(world.bodyStates());return result;}
          catch(InvocationTargetException failure){throw failure.getCause();}
        });
      }
    };
  }
  static PhysicsProvider perturbed(PhysicsProvider provider) {
    return new PhysicsProvider() {
      public String id(){return provider.id();}
      public PhysicsWorld createWorld(PhysicsSpec.World settings) {
        var g=settings.gravity();return provider.createWorld(new PhysicsSpec.World(new Vec3(g.x(),Math.nextUp(g.y()),g.z()),settings.stepSeconds(),settings.solverIterations(),settings.recordReplay(),settings.kinematicFilter()));
      }
    };
  }
  static PhysicsProvider isolated(PhysicsProvider provider,String mode) {
    if(mode.isEmpty())return provider;
    return new PhysicsProvider() {
      public String id(){return provider.id();}
      public PhysicsWorld createWorld(PhysicsSpec.World settings) {
        var world=provider.createWorld(settings);
        return (PhysicsWorld)Proxy.newProxyInstance(PhysicsWorld.class.getClassLoader(),new Class<?>[]{PhysicsWorld.class},(proxy,method,args)->{
          if(method.getName().equals("addBody") && mode.contains("contacts")) {
            var b=(PhysicsSpec.Body)args[0];args[0]=new PhysicsSpec.Body(b.shape(),b.dimensions(),b.pose(),b.motion(),b.mass(),b.linearDamping(),b.angularDamping(),b.restitution(),b.friction(),b.margin(),b.collisionGroup(),0);
          }
          if(mode.contains("joints")) {
            if(method.getName().equals("addJoint"))return 0;
            if(method.getName().equals("configureJoint"))return null;
          }
          try{return method.invoke(world,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
      }
    };
  }
  static float[] difference(MmdPlayback.Frame expected,MmdPlayback.Frame actual) {
    float bones=0,vertices=0,normals=0;
    for(int i=0;i<expected.pose().bones().size();i++){var e=expected.pose().bones().get(i).matrix().copy();var a=actual.pose().bones().get(i).matrix().copy();for(int j=0;j<e.length;j++)bones=Math.max(bones,Math.abs(e[j]-a[j]));}
    for(int mesh=0;mesh<expected.primitives().size();mesh++)for(String attribute:List.of("POSITION","NORMAL")) {
      var e=expected.primitives().get(mesh).get(attribute).values();var a=actual.primitives().get(mesh).get(attribute).values();
      for(int j=0;j<e.size();j++){float error=Math.abs(e.get(j)-a.get(j));if(!Float.isFinite(error))throw new IllegalStateException("Non-finite corpus output");if(attribute.equals("POSITION"))vertices=Math.max(vertices,error);else normals=Math.max(normals,error);}
    }
    return new float[]{bones,vertices,normals};
  }
  enum Variant { CLIP, FOUR_LAYERS, VPD_EXPRESSION_IK, IMPULSE_RESET, RS_POLICY }
  static PmxDocument variantModel(PmxDocument source,Variant variant) {
    if(variant!=Variant.IMPULSE_RESET)return source;
    int body=-1;for(int i=0;i<source.rigidBodies().size();i++)if(source.rigidBodies().get(i).mode()!=0 && source.rigidBodies().get(i).mass()>0){body=i;break;}
    if(body<0)throw new IllegalArgumentException("Impulse corpus requires a dynamic rigid body");
    var morphs=new ArrayList<>(source.morphs());
    morphs.add(new PmxDocument.Morph(new PmxDocument.Names("@corpus/impulse",""),4,10,List.of(new PmxDocument.ImpulseOffset(body,true,new Vec3(.1f,.2f,0),new Vec3(0,.05f,0)))));
    morphs.add(new PmxDocument.Morph(new PmxDocument.Names("@corpus/reset",""),4,10,List.of(new PmxDocument.ImpulseOffset(body,false,Vec3.ZERO,Vec3.ZERO))));
    return new PmxDocument(source.version(),source.globals(),source.names(),source.comment(),source.englishComment(),source.vertices(),source.indices(),source.textures(),source.materials(),source.bones(),morphs,source.displayFrames(),source.rigidBodies(),source.joints(),source.softBodies(),source.source());
  }
  static MmdPlayer player(PmxDocument model,AnimationClip clip,PhysicsProvider provider,Variant variant) {
    var settings=MmdPlayback.Settings.preview();
    if(variant==Variant.RS_POLICY)settings=settings.withPhysicsPolicy(true,true,true);
    MmdPlayer result;boolean packed=Boolean.getBoolean("ysm.native.physics.corpus.packed");
    if(variant==Variant.FOUR_LAYERS) {
      var layers=new MmdAnimationLayers(model);layers.clip(0,clip);layers.play(0);
      layers.clip(1,clip);layers.settings(1,new MmdAnimationLayers.Settings(.5f,2,true,.2,.2));layers.play(1);
      layers.clip(2,clip);layers.settings(2,new MmdAnimationLayers.Settings(.25f,.5f,false,0,0));layers.boneMask(2,Set.of(0),Set.of());layers.play(2);
      layers.settings(3,new MmdAnimationLayers.Settings(.1f,1,true,0,0));layers.transition(3,clip,.75);
      result=packed?MmdPlayer.packed(model,layers.freeze(),provider,settings,Map.of()):new MmdPlayer(model,layers.freeze(),provider,settings,Map.of());
    } else {
      if(variant==Variant.IMPULSE_RESET) {
        var tracks=new ArrayList<>(clip.tracks());
        for(int i=0;i<2;i++)tracks.add(new AnimationClip.Track(AnimationClip.Property.MORPH_WEIGHTS,model.morphs().size()-2+i,"","",
            new AnimationCurve(new double[]{0,.2,.3,.7,.8},new FloatData(0,1,0,1,0),1,false,AnimationCurve.Interpolation.STEP,FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY)));
        clip=new AnimationClip("impulse/reset corpus",tracks,clip.sourceFramesPerSecond());
      }
      result=packed?MmdPlayer.packed(model,clip,provider,settings,Map.of()):new MmdPlayer(model,clip,provider,settings,Map.of());
    }
    if(variant==Variant.VPD_EXPRESSION_IK) {
      var name=model.bones().get(0).names().local();
      result.vpdPose(new VpdDocument("generated corpus overlay",List.of(new VpdDocument.BonePose(0,name,new Vec3(.1f,.2f,0),new FloatData(0,0,0,1))),List.of(),ByteData.EMPTY));
      if(!model.morphs().isEmpty())result.morphWeights(Map.of(0,.3f));
      var ik=new LinkedHashMap<Integer,Boolean>();for(int i=0;i<model.bones().size();i++)if(model.bones().get(i).ik()!=null)ik.put(i,false);result.ikOverrides(ik);
      result.boneRotations(Map.of(0,Rotation.axisAngle(new Vec3(0,1,0),.2)));
    }
    return result;
  }
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]),output=Path.of(args[1]);var models=new ArrayList<Path>();var motions=new ArrayList<Path>();
    try(var files=Files.walk(root)){files.filter(Files::isRegularFile).sorted().forEach(p->{String name=p.getFileName().toString().toLowerCase(Locale.ROOT);if(name.endsWith(".pmx"))models.add(p);if(name.endsWith(".vmd"))motions.add(p);});}
    if(models.isEmpty() || motions.isEmpty())throw new IllegalArgumentException("Corpus needs PMX and VMD files");
    String isolation=System.getProperty("ysm.native.physics.corpus.isolation","");
    var nativeProvider=isolated(new NativePhysicsProvider(Path.of(Objects.requireNonNull(System.getProperty("ysm.native.physics.library")))),isolation);var wasm=isolated(new WasmPhysicsProvider(),isolation);
    if(!isolation.isEmpty())System.out.println("Diagnostic only: disabled "+isolation);
    boolean debug=Boolean.getBoolean("ysm.native.physics.corpus.debug");
    var variants=Boolean.getBoolean("ysm.native.physics.corpus.extended")?Variant.values():new Variant[]{Variant.CLIP};
    var rows=new ArrayList<String>();rows.add("model,motion,variant,modelSha256,motionSha256,bodies,joints,softBodies,boneMaxError,vertexMaxError,normalMaxError,passed");int failures=0,comparisons=0;
    for(var path:models) {
      var modelData=new ByteData(Files.readAllBytes(path));var model=new PmxReader().read(modelData,ReadLimits.DEFAULT);
      for(var motionPath:motions) for(var variant:variants) {
        var motionData=new ByteData(Files.readAllBytes(motionPath));var vmd=new VmdReader().read(motionData,ReadLimits.DEFAULT);if(vmd.bones().isEmpty())continue;
        var clip=new VmdAnimation().compile(vmd).clip();float[] maximum=new float[3];
        var evaluated=variantModel(model,variant);
        var nt=new ArrayList<List<PhysicsSpec.BodyState>>();var wt=new ArrayList<List<PhysicsSpec.BodyState>>();
        try(var n=player(evaluated,clip,debug?traced(nativeProvider,nt):nativeProvider,variant);var w=player(evaluated,clip,debug?traced(wasm,wt):wasm,variant);
            var control=debug?player(evaluated,clip,perturbed(nativeProvider),variant):null) {
          for(double time:new double[]{0,.25,.5,.75,1,.5,.503,0,1}) {
            var difference=difference(w.seek(time),n.seek(time));for(int i=0;i<3;i++)maximum[i]=Math.max(maximum[i],difference[i]);
            if(debug)System.out.println("time="+time+" errors="+Arrays.toString(difference)+" oneUlpGravitySensitivity="+Arrays.toString(difference(n.current(),control.seek(time))));
          }
        }
        if(debug) {
          for(int step=0;step<Math.min(nt.size(),wt.size());step++) {
            float worst=0;int body=-1;
            for(int i=0;i<nt.get(step).size();i++){var delta=nt.get(step).get(i).pose().position().subtract(wt.get(step).get(i).pose().position());float error=(float)Math.sqrt(delta.dot(delta));if(error>worst){worst=error;body=i;}}
            if(step<5 || step%15==14)System.out.println("solverStep="+step+" maxPositionError="+worst+" body="+body+(body<0?"":" name="+model.rigidBodies().get(body).names().local()+" native="+nt.get(step).get(body)+" wasm="+wt.get(step).get(body)));
          }
        }
        boolean passed=maximum[0]<=.05f && maximum[1]<=.05f && maximum[2]<=.02f;if(!passed)failures++;comparisons++;
        String row=String.format(Locale.ROOT,"%s,%s,%s,%s,%s,%d,%d,%d,%.8f,%.8f,%.8f,%s",quote(root.relativize(path)),quote(root.relativize(motionPath)),variant,hash(modelData),hash(motionData),model.rigidBodies().size(),model.joints().size(),model.softBodies().size(),maximum[0],maximum[1],maximum[2],passed);
        rows.add(row);System.out.println(row);
        if(debug)break;
      }
      if(debug)break;
    }
    Files.createDirectories(output.toAbsolutePath().getParent());Files.write(output,rows);
    if(comparisons==0 || failures!=0)throw new IllegalStateException("Corpus failures="+failures+" comparisons="+comparisons);
    System.out.println("Corpus passed: "+comparisons+" PMX/VMD pairs, including backward and fractional seek");
  }
}
