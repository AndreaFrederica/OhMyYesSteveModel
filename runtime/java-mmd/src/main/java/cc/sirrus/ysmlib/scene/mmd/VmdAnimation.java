package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.AnimationClip.Property.*;
import static cc.sirrus.ysmlib.scene.AnimationCurve.Interpolation.*;

/** Builds bound-by-name tracks while the VmdDocument retains every original source record. */
public final class VmdAnimation {
  private record Target(AnimationClip.Property property,String name) {}
  private record Key(long frame,float[] value,float[] bezier) {}
  private static final class Channel {
    final Target target;final int components;final boolean quaternion,cameraCut;
    final AnimationCurve.Interpolation interpolation;final TreeMap<Long,Key> keys=new TreeMap<>();
    Channel(Target target,int components,boolean quaternion,boolean cameraCut,AnimationCurve.Interpolation interpolation) {
      this.target=target;this.components=components;this.quaternion=quaternion;this.cameraCut=cameraCut;this.interpolation=interpolation;
    }
  }
  public AnimationImport compile(VmdDocument document) {
    var channels=new LinkedHashMap<Target,Channel>();
    var diagnostics=new ArrayList<CompatibilityReport.Diagnostic>();
    for(var key:document.bones()) {
      add(channels,diagnostics,TRANSLATION,key.name(),key.frame(),vec(key.translation()),false,false,BEZIER,boneBezier(key.interpolation(),0,1,2));
      add(channels,diagnostics,ROTATION,key.name(),key.frame(),key.rotation().copy(),true,false,BEZIER,boneBezier(key.interpolation(),3));
    }
    for(var key:document.morphs()) add(channels,diagnostics,MORPH_WEIGHTS,key.name(),key.frame(),new float[]{key.weight()},false,false,LINEAR,null);
    for(var key:document.cameras()) {
      add(channels,diagnostics,CAMERA_TARGET,"",key.frame(),vec(key.target()),false,true,BEZIER,cameraBezier(key.interpolation(),0,1,2));
      add(channels,diagnostics,CAMERA_EULER,"",key.frame(),vec(key.euler()),false,true,BEZIER,cameraBezier(key.interpolation(),3,3,3));
      add(channels,diagnostics,CAMERA_DISTANCE,"",key.frame(),new float[]{key.distance()},false,true,BEZIER,cameraBezier(key.interpolation(),4));
      add(channels,diagnostics,CAMERA_FOV,"",key.frame(),new float[]{(float)Math.toRadians(key.fovDegrees())},false,true,BEZIER,cameraBezier(key.interpolation(),5));
      add(channels,diagnostics,CAMERA_PERSPECTIVE,"",key.frame(),new float[]{key.perspective()?1:0},false,false,STEP,null);
    }
    for(var key:document.lights()) {
      add(channels,diagnostics,LIGHT_COLOR,"",key.frame(),vec(key.color()),false,false,LINEAR,null);
      add(channels,diagnostics,LIGHT_DIRECTION,"",key.frame(),vec(key.direction()),false,false,LINEAR,null);
    }
    for(var key:document.shadows()) {
      add(channels,diagnostics,SHADOW_MODE,"",key.frame(),new float[]{key.mode()},false,false,STEP,null);
      add(channels,diagnostics,SHADOW_DISTANCE,"",key.frame(),new float[]{key.distance()},false,false,LINEAR,null);
    }
    for(var key:document.properties()) {
      add(channels,diagnostics,DISPLAY,"",key.frame(),new float[]{key.visible()?1:0},false,false,STEP,null);
      for(var ik:key.ik()) add(channels,diagnostics,IK_ENABLED,ik.name(),key.frame(),new float[]{ik.enabled()?1:0},false,false,STEP,null);
    }
    var tracks=new ArrayList<AnimationClip.Track>();
    for(var channel:channels.values()) {
      int n=channel.keys.size(),stride=channel.components,cpStride=(channel.quaternion?1:stride)*4;
      double[] times=new double[n];float[] values=new float[n*stride],bezier=channel.interpolation==BEZIER?new float[n*cpStride]:new float[0];
      var held=new ArrayList<Integer>();int index=0;long previous=-2;
      for(var key:channel.keys.values()) {
        times[index]=key.frame/30.0;System.arraycopy(key.value,0,values,index*stride,stride);
        if(key.bezier!=null) System.arraycopy(key.bezier,0,bezier,index*cpStride,cpStride);
        if(channel.cameraCut && previous+1==key.frame) held.add(index-1);
        previous=key.frame;index++;
      }
      var curve=new AnimationCurve(times,new FloatData(values),stride,channel.quaternion,channel.interpolation,
          FloatData.EMPTY,FloatData.EMPTY,new FloatData(bezier),new IntData(held.stream().mapToInt(Integer::intValue).toArray()));
      tracks.add(new AnimationClip.Track(channel.target.property,-1,channel.target.name,"",curve));
    }
    var features=List.of(new CompatibilityReport.Feature("vmd.source",CompatibilityReport.Level.READ,false,"All six sections retained; source coordinates and stored shadow distance preserved"),
        new CompatibilityReport.Feature("vmd.curves",CompatibilityReport.Level.EVALUATED,true,"Bezier, quaternion, linear, step, camera cuts; binding and MMD solver are separate"));
    return new AnimationImport(new AnimationClip(document.modelName(),tracks,30),new CompatibilityReport(features,diagnostics));
  }
  private static void add(Map<Target,Channel> channels,List<CompatibilityReport.Diagnostic> diagnostics,AnimationClip.Property property,
      String name,long frame,float[] value,boolean quaternion,boolean cameraCut,AnimationCurve.Interpolation interpolation,float[] bezier) {
    var target=new Target(property,name);
    var channel=channels.computeIfAbsent(target,t->new Channel(t,value.length,quaternion,cameraCut,interpolation));
    if(channel.keys.put(frame,new Key(frame,value,bezier))!=null)
      diagnostics.add(new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.WARNING,property+":"+name+":"+frame,
          "Duplicate source key; the last source record is evaluated, all original records remain in the document"));
  }
  private static float[] vec(Vec3 v) { return new float[]{v.x(),v.y(),v.z()}; }
  private static float[] boneBezier(ByteData data,int... channels) {
    float[] out=new float[channels.length*4];int index=0;
    for(int c:channels) for(int offset:new int[]{0,4,8,12}) out[index++]=data.unsigned(c+offset)/127f;
    return out;
  }
  private static float[] cameraBezier(ByteData data,int... channels) {
    float[] out=new float[channels.length*4];int index=0;
    for(int c:channels) for(int offset:new int[]{0,2,1,3}) out[index++]=data.unsigned(c*4+offset)/127f;
    return out;
  }
}
