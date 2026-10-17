package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.*;
import java.util.*;

/** Samples authored/source overlays before constraints and physics. Bone and morph masks are independent. */
final class SceneLayerMixer {
  private record Key(AnimationClip.Property property,int index,String binding,String component) {}
  private final SceneProvider services;
  private final ScenePackageAssets assets;
  private final SceneModelProfile profile;
  private final Map<ScenePackagePlayback.Selection,AnimationClip> clips=new HashMap<>();
  private final Map<ScenePackagePlayback.Selection,cc.sirrus.ysmlib.scene.vrm.VrmaEvaluation> vrma=new HashMap<>();
  private final Map<ScenePackagePlayback.Layer,cc.sirrus.ysmlib.scene.vrm.VrmaEvaluation.Sample> vrmaSamples=new HashMap<>();
  private final Map<String,Integer> bones=new HashMap<>(),morphs=new HashMap<>();
  private List<ScenePackagePlayback.Layer> layers=List.of();
  private Map<Integer,Pose> sourcePoses=Map.of();
  private final int boneCount;
  private final Map<ScenePackagePlayback.Layer,AnimationFrame> layerSamples=new HashMap<>();
  void sourcePoses(Map<Integer,Pose> poses){sourcePoses=Map.copyOf(poses);layerSamples.clear();}
  boolean controller(){return layers.stream().anyMatch(l->l.selection().sourceId().equals("@ysm/controller")&&l.weight()>0);}
  Map<String,Float> semantic(Map<String,Float> base){if(assets.model() instanceof ScenePackageAssets.Pmx||assets.model() instanceof ScenePackageAssets.Pmd)return base;var out=new LinkedHashMap<>(base);
    for(var l:layers)if(l.weight()>0&&!l.expressions().isEmpty()){
      if(assets.documents().get(l.selection().sourceId()) instanceof ScenePackageAssets.Vrma)continue;
      if(!l.selection().sourceId().startsWith("@ysm/profile/"))throw new IllegalArgumentException("Semantic scene layers require authored expression tracks");
      var clip=profile.features().animations().get(l.selection().sourceId().substring(13));for(var track:clip.tracks())if(track.kind()==SceneModelFeatures.Target.EXPRESSION&&l.expressions().contains(track.binding())){float from=out.getOrDefault(track.binding(),0f),to=CurveSampler.sample(track.curve(),l.seconds())[0];out.put(track.binding(),from+(to-from)*l.weight());}
    }return out;
  }
  SceneLayerMixer(SceneProvider services,ScenePackageAssets assets,SceneModelProfile profile){this.services=services;this.assets=assets;this.profile=profile;
    boneCount=SceneSkeleton.of(assets).size();
    for(var b:SceneSkeleton.of(assets)){unique(bones,b.name(),b.index());if(!b.alternate().isEmpty())unique(bones,b.alternate(),b.index());}
    if(assets.model() instanceof ScenePackageAssets.Pmx p)for(int i=0;i<p.value().morphs().size();i++){var n=p.value().morphs().get(i).names();unique(morphs,n.local(),i);if(!n.universal().isEmpty())unique(morphs,n.universal(),i);}
    if(assets.model() instanceof ScenePackageAssets.Pmd p)for(int i=0;i<p.value().morphs().size();i++)unique(morphs,p.value().morphs().get(i).name(),i);
  }
  private static void unique(Map<String,Integer> map,String name,int index){map.merge(name,index,(a,b)->a.equals(b)?a:-1);}
  void layers(List<ScenePackagePlayback.Layer> next){next=List.copyOf(next);for(var l:next){if(!l.selection().sourceId().equals("@ysm/controller"))resolve(l.selection());else if(l.selection().clip()!=-1||!(assets.model() instanceof ScenePackageAssets.Pmx||assets.model() instanceof ScenePackageAssets.Pmd||assets.model() instanceof ScenePackageAssets.Vrm)||!l.morphs().isEmpty())throw new IllegalArgumentException("Invalid controller pose layer");for(int b:l.bones())if(b<0||b>=boneCount)throw new IllegalArgumentException("Layer bone outside source");
    for(String name:l.expressions())if(!profile.features().expressions().containsKey(name))throw new IllegalArgumentException("Unknown layer expression: "+name);
    if(!(assets.model() instanceof ScenePackageAssets.Pmx||assets.model() instanceof ScenePackageAssets.Pmd)&&!l.expressions().isEmpty()&&!l.selection().sourceId().startsWith("@ysm/profile/")&&!(assets.documents().get(l.selection().sourceId()) instanceof ScenePackageAssets.Vrma))throw new IllegalArgumentException("Semantic scene layers require authored or VRMA expression tracks");
    int morphCount=assets.model() instanceof ScenePackageAssets.Pmx p?p.value().morphs().size():assets.model() instanceof ScenePackageAssets.Pmd p?p.value().morphs().size():boneCount;
    for(int m:l.morphs())if(m<0||m>=morphCount)throw new IllegalArgumentException("Layer morph outside source");}layers=next;layerSamples.clear();vrmaSamples.clear();}
  Set<Integer> ownedBones(){var out=new HashSet<Integer>();for(var l:layers)if(l.weight()>0)out.addAll(l.bones());return out;}
  Map<Integer,Float> referenceWeights(Set<Integer> hostBones){var out=new HashMap<Integer,Float>();hostBones.forEach(b->out.put(b,1f));
    for(var layer:layers)if(layer.weight()>0){boolean controller=layer.selection().sourceId().equals("@ysm/controller");
      for(var channel:bound(layerFrame(layer)).channels())if(channel.property()==AnimationClip.Property.ROTATION&&layer.bones().contains(channel.targetIndex())){
        int bone=channel.targetIndex();float from=out.getOrDefault(bone,0f);out.put(bone,from+((controller?1:0)-from)*layer.weight());
      }}out.values().removeIf(w->w<=0);return Map.copyOf(out);
  }
  private AnimationClip resolve(ScenePackagePlayback.Selection s){return clips.computeIfAbsent(s,key->{
    if(s.sourceId().startsWith("@ysm/profile/"))return SceneFeatureBindings.clip(profile,s.sourceId().substring(13),assets.model() instanceof ScenePackageAssets.Pmx||assets.model() instanceof ScenePackageAssets.Pmd,false);
    var d=assets.documents().get(s.sourceId());if(d instanceof ScenePackageAssets.Vmd v&&s.clip()==0)return v.animation().clip();
    if(d instanceof ScenePackageAssets.Vrma v&&assets.model() instanceof ScenePackageAssets.Vrm target){if(s.clip()<0||s.clip()>=v.value().animations().size())throw new IllegalArgumentException("VRMA layer clip out of range");var evaluator=services.retarget(v.value(),target.value(),cc.sirrus.ysmlib.scene.vrm.VrmaEvaluation.Settings.automatic());if(!evaluator.compatibility().canEvaluateRequiredFeatures())throw new IllegalArgumentException("Unsupported VRMA layer behavior");vrma.put(s,evaluator);return new AnimationClip("retargeted VRMA layer",List.of(),0);}
    if(d instanceof ScenePackageAssets.Vpd v&&s.clip()==0)return v.animation();
    if(d instanceof ScenePackageAssets.Gltf g&&s.sourceId().equals(assets.source().model().id()))return g.value().scene().animations().get(s.clip());
    if(d instanceof ScenePackageAssets.Vrm v&&s.sourceId().equals(assets.source().model().id()))return v.value().scene().animations().get(s.clip());
    throw new IllegalArgumentException("Layer source cannot be evaluated: "+s);
  });}
  private AnimationFrame bound(AnimationFrame frame){var out=new ArrayList<AnimationFrame.Channel>();for(var c:frame.channels()){
    int index=c.targetIndex();if(index<0){Integer n=(c.property()==AnimationClip.Property.MORPH_WEIGHTS?morphs:bones).get(c.binding());if(n!=null&&n>=0)index=n;}
    out.add(new AnimationFrame.Channel(c.property(),index,index<0?c.binding():"",c.component(),c.value()));}return new AnimationFrame(frame.seconds(),out);}
  AnimationFrame poseBase(AnimationFrame frame,Map<Integer,Pose> poses){if(poses.isEmpty()||layers.isEmpty())return frame;var out=new ArrayList<>(frame.channels());
    poses.forEach((index,pose)->{var p=pose.position();var q=pose.rotation();out.add(new AnimationFrame.Channel(AnimationClip.Property.TRANSLATION,index,"","",new FloatData(p.x(),p.y(),p.z())));out.add(new AnimationFrame.Channel(AnimationClip.Property.ROTATION,index,"","",new FloatData(q.x(),q.y(),q.z(),q.w())));});return new AnimationFrame(frame.seconds(),out);}
  private AnimationFrame poseFrame(double seconds,Map<Integer,Pose> poses){return poseBase(new AnimationFrame(seconds,List.of()),poses);}
  private AnimationFrame layerFrame(ScenePackagePlayback.Layer layer){return layerSamples.computeIfAbsent(layer,this::sampleLayer);}
  private cc.sirrus.ysmlib.scene.vrm.VrmaEvaluation.Sample vrmaSample(ScenePackagePlayback.Layer layer){return vrmaSamples.computeIfAbsent(layer,l->vrma.get(l.selection()).sample(l.selection().clip(),l.seconds()));}
  Map<String,Float> vrmExpressions(Map<String,Float> base){
    var output=new LinkedHashMap<>(base);
    for(var layer:layers)if(layer.weight()>0){
      var desired=new LinkedHashMap<String,Float>();
      if(vrma.containsKey(layer.selection())){
        var sampled=vrmaSample(layer).input().expressions();
        for(String semantic:layer.expressions())for(var target:profile.features().expressions().get(semantic).targets())
          if(target.kind()==SceneModelFeatures.Target.VRM_EXPRESSION&&sampled.containsKey(target.name()))desired.put(target.name(),sampled.get(target.name()));
      }else if(layer.selection().sourceId().startsWith("@ysm/profile/")){
        var clip=profile.features().animations().get(layer.selection().sourceId().substring(13));
        for(var track:clip.tracks())if(track.kind()==SceneModelFeatures.Target.EXPRESSION&&layer.expressions().contains(track.binding())){
          float value=CurveSampler.sample(track.curve(),layer.seconds())[0];
          for(var target:profile.features().expressions().get(track.binding()).targets())if(target.kind()==SceneModelFeatures.Target.VRM_EXPRESSION)desired.merge(target.name(),value*target.weight(),Math::max);
        }
      }
      desired.forEach((name,value)->{float from=output.getOrDefault(name,0f);output.put(name,from+(value-from)*layer.weight());});
    }
    return output;
  }
  private AnimationFrame sampleLayer(ScenePackagePlayback.Layer layer){if(layer.selection().sourceId().equals("@ysm/controller"))return poseFrame(layer.seconds(),sourcePoses);
    if(vrma.containsKey(layer.selection())){var sample=vrmaSample(layer);var channels=new ArrayList<AnimationFrame.Channel>();for(int n:layer.bones()){
      var p=SceneRig.position(sample.pose().localMatrices().get(n));var q=sample.rotations().get(n);channels.add(new AnimationFrame.Channel(AnimationClip.Property.TRANSLATION,n,"","",new FloatData(p.x(),p.y(),p.z())));channels.add(new AnimationFrame.Channel(AnimationClip.Property.ROTATION,n,"","",new FloatData(q.x(),q.y(),q.z(),q.w())));
    }return new AnimationFrame(layer.seconds(),channels);}
    var frame=services.sample(resolve(layer.selection()),layer.seconds());
    if(!(assets.model() instanceof ScenePackageAssets.Pmx||assets.model() instanceof ScenePackageAssets.Pmd)||!layer.selection().sourceId().startsWith("@ysm/profile/"))return frame;
    var weights=new LinkedHashMap<Integer,Float>();var clip=profile.features().animations().get(layer.selection().sourceId().substring(13));
    for(var t:clip.tracks())if(t.kind()==SceneModelFeatures.Target.EXPRESSION){float value=CurveSampler.sample(t.curve(),layer.seconds())[0];for(var target:profile.features().expressions().get(t.binding()).targets())weights.merge(target.index(),value*target.weight(),Math::max);}
    var out=new ArrayList<>(frame.channels());weights.forEach((index,value)->out.add(new AnimationFrame.Channel(AnimationClip.Property.MORPH_WEIGHTS,index,"","",new FloatData(value))));return new AnimationFrame(frame.seconds(),out);
  }
  AnimationFrame apply(AnimationFrame base){if(layers.isEmpty())return base;var values=new LinkedHashMap<Key,AnimationFrame.Channel>();
    for(var c:bound(base).channels())values.put(new Key(c.property(),c.targetIndex(),c.binding(),c.component()),c);
    for(var l:layers)if(l.weight()>0)for(var c:bound(layerFrame(l)).channels()){
      boolean bone=c.property()==AnimationClip.Property.TRANSLATION||c.property()==AnimationClip.Property.ROTATION||c.property()==AnimationClip.Property.SCALE||c.property()==AnimationClip.Property.IK_ENABLED;
      if(bone?!l.bones().contains(c.targetIndex()):c.property()!=AnimationClip.Property.MORPH_WEIGHTS||!l.morphs().contains(c.targetIndex()))continue;
      var key=new Key(c.property(),c.targetIndex(),c.binding(),c.component());var old=values.get(key);var from=old==null?identity(c):old.value();var to=c.value();
      FloatData value;if(c.property()==AnimationClip.Property.ROTATION){var q=Rotation.slerp(Rotation.normalized(from.get(0),from.get(1),from.get(2),from.get(3)),Rotation.normalized(to.get(0),to.get(1),to.get(2),to.get(3)),l.weight());value=new FloatData(q.x(),q.y(),q.z(),q.w());}
      else {if(from.size()!=to.size())throw new IllegalArgumentException("Layer width mismatch");float[] v=to.copy();for(int i=0;i<v.length;i++)v[i]=from.get(i)+(v[i]-from.get(i))*l.weight();value=new FloatData(v);}
      values.put(key,new AnimationFrame.Channel(c.property(),c.targetIndex(),c.binding(),c.component(),value));
    }return new AnimationFrame(base.seconds(),new ArrayList<>(values.values()));}
  private FloatData identity(AnimationFrame.Channel c){var scene=SceneFeatureBindings.scene(assets);
    if(scene!=null&&c.targetIndex()>=0){var node=scene.nodes().get(c.targetIndex());var t=node.transform();if(node.matrix()!=null)throw new IllegalArgumentException("Layer TRS requires a TRS node");
      if(c.property()==AnimationClip.Property.ROTATION){var q=t.rotation();return new FloatData(q.x(),q.y(),q.z(),q.w());}
      if(c.property()==AnimationClip.Property.TRANSLATION){var v=t.translation();return new FloatData(v.x(),v.y(),v.z());}
      if(c.property()==AnimationClip.Property.SCALE){var v=t.scale();return new FloatData(v.x(),v.y(),v.z());}
      if(c.property()==AnimationClip.Property.MORPH_WEIGHTS){var weights=node.morphWeights();if(weights.size()==0&&node.mesh()>=0)weights=scene.meshes().get(node.mesh()).defaultMorphWeights();return weights.size()==0?new FloatData(new float[c.value().size()]):weights;}
  }
    if(c.property()==AnimationClip.Property.ROTATION)return new FloatData(0,0,0,1);
    if(c.property()==AnimationClip.Property.SCALE)return new FloatData(1,1,1);if(c.property()==AnimationClip.Property.IK_ENABLED)return new FloatData(1);return new FloatData(new float[c.value().size()]);}
  AnimationClip sampledClip(AnimationClip source,double time){if(layers.isEmpty())return source;var frame=apply(source==null?new AnimationFrame(time,List.of()):services.sample(source,time));var out=new ArrayList<AnimationClip.Track>();for(var c:frame.channels())
    out.add(new AnimationClip.Track(c.property(),c.targetIndex(),c.binding(),c.component(),new AnimationCurve(new double[]{0},c.value(),c.value().size(),c.property()==AnimationClip.Property.ROTATION,AnimationCurve.Interpolation.STEP,FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY)));
    return new AnimationClip("instance sample",out,0);}

  /** Apply local node layers directly; do not allocate synthetic keyframe curves on every physics substep. */
  ScenePose nodePose(ScenePose base,SceneRig rig,Map<Integer,Pose> host) {
    var pose=rig.deltas(base,host);if(layers.isEmpty())return pose;
    var local=new ArrayList<>(pose.localMatrices());var morphs=new ArrayList<>(pose.morphWeights());boolean changed=false;
    var scene=SceneFeatureBindings.scene(assets);
    for(var layer:layers)if(layer.weight()>0)for(var c:layerFrame(layer).channels()) {
      int n=c.targetIndex();if(n<0||n>=boneCount)throw new IllegalArgumentException("Unbound node layer");
      if(c.property()==AnimationClip.Property.MORPH_WEIGHTS) {
        if(!layer.morphs().contains(n))continue;var before=morphs.get(n);if(before.size()!=c.value().size())throw new IllegalArgumentException("Layer morph width mismatch");
        var values=c.value().copy();for(int j=0;j<values.length;j++)values[j]=before.get(j)+(values[j]-before.get(j))*layer.weight();morphs.set(n,new FloatData(values));continue;
      }
      if(!layer.bones().contains(n))continue;var m=local.get(n);var value=c.value();boolean controller=layer.selection().sourceId().equals("@ysm/controller");
      switch(c.property()) {
        case ROTATION -> {
          var next=Rotation.normalized(value.get(0),value.get(1),value.get(2),value.get(3));if(controller)next=rig.restRotations().get(n).multiply(next);
          var before=SceneRig.rotation(m);local.set(n,SceneRig.withRotation(m,before,Rotation.slerp(before,next,layer.weight())));changed=true;
        }
        case TRANSLATION -> {
          var next=new Vec3(value.get(0),value.get(1),value.get(2));if(controller)next=SceneRig.position(scene.nodes().get(n).localMatrix()).add(next);
          var before=SceneRig.position(m);var p=before.add(next.subtract(before).multiply(layer.weight()));var values=m.copy();values[12]=p.x();values[13]=p.y();values[14]=p.z();local.set(n,new Matrix4(values));changed=true;
        }
        case SCALE -> {
          var rotation=SceneRig.rotation(m);var x=SceneRig.column(m,0);float sx=(float)Math.sqrt(x.dot(x));if(SceneRig.column(m,0).dot(SceneRig.column(m,1).cross(SceneRig.column(m,2)))<0)sx=-sx;
          var y=SceneRig.column(m,1);var z=SceneRig.column(m,2);var before=new Vec3(sx,(float)Math.sqrt(y.dot(y)),(float)Math.sqrt(z.dot(z)));
          var next=new Vec3(value.get(0),value.get(1),value.get(2));local.set(n,new Transform(SceneRig.position(m),rotation,before.add(next.subtract(before).multiply(layer.weight()))).matrix());changed=true;
        }
        default -> throw new IllegalArgumentException("Unsupported node pose layer: "+c.property());
      }
    }
    return new ScenePose(pose.seconds(),local,changed?rig.global(local):pose.globalMatrices(),morphs,pose.extensionChannels());
  }
}
