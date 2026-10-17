package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.CurveSampler;
import java.util.*;

/** One instance's semantic inputs. Source deformation/VRM rules still run in their format evaluators. */
final class SceneFeatureRuntime {
  final SceneModelProfile profile;
  private Map<String,Float> manual=Map.of();
  double yaw,pitch,lastTime;
  private String faceAnimation;
  private double faceSeconds;
  java.util.function.UnaryOperator<Map<String,Float>> layerSemantics=map->map;
  void expressionAnimation(String name,double seconds){
    if(!Double.isFinite(seconds)||seconds<0)throw new IllegalArgumentException("Invalid face animation time");
    if(name!=null){var clip=profile.features().animations().get(name);if(clip==null||clip.tracks().stream().anyMatch(t->t.kind()!=SceneModelFeatures.Target.EXPRESSION))throw new IllegalArgumentException("Independent face animation requires expression-only tracks");}
    faceAnimation=name;faceSeconds=seconds;
  }
  private record Owner(float fromWeight,float toWeight,float fromAlpha,float toAlpha,double start,double duration) {
    float progress(double time){return duration==0?1:(float)Math.max(0,Math.min(1,(time-start)/duration));}
    float weight(double time){return fromWeight+(toWeight-fromWeight)*progress(time);}
    float alpha(double time){return fromAlpha+(toAlpha-fromAlpha)*progress(time);}
  }
  private final Map<String,Owner> owners=new LinkedHashMap<>();
  private final Map<Integer,IntData> firstIndices=new HashMap<>();
  SceneFeatureRuntime(SceneModelProfile profile,ScenePackageAssets assets){this.profile=profile;
    var fp=profile.features().firstPerson();if(!fp.hiddenBones().isEmpty()&&!(assets.model() instanceof ScenePackageAssets.Vrm)) {
      var rig=SceneSkeleton.of(assets);var hidden=new HashSet<Integer>();fp.hiddenBones().forEach(b->hidden.add(SceneFeatureBindings.requiredBone(profile,b)));
      boolean changed;do{changed=false;for(var b:rig)if(hidden.contains(b.parent())&&hidden.add(b.index()))changed=true;}while(changed);
      var mesh=assets.model() instanceof ScenePackageAssets.Pmx p?p.mesh():((ScenePackageAssets.Pmd)assets.model()).mesh();
      for(var primitive:mesh.primitives()) {
        if(primitive.topology()!=MeshAsset.Topology.TRIANGLES)throw new IllegalArgumentException("First-person bone filtering requires triangle topology");
        var skin=primitive.skinning();if(skin==null)throw new IllegalArgumentException("First-person bone filtering requires skin weights");
        var visible=new boolean[primitive.vertexCount()];for(int v=0;v<visible.length;v++){float head=0,total=0;for(int i=skin.offsets().get(v);i<skin.offsets().get(v+1);i++){float w=skin.weights().get(i);total+=w;if(hidden.contains(skin.joints().get(i)))head+=w;}visible[v]=total==0||head/total<fp.boneThreshold();}
        int[] indices=primitive.indices().copy(),out=new int[indices.length];int count=0;
        for(int i=0;i<indices.length;i+=3)if(visible[indices[i]]&&visible[indices[i+1]]&&visible[indices[i+2]]){out[count++]=indices[i];out[count++]=indices[i+1];out[count++]=indices[i+2];}
        firstIndices.put(primitive.material(),new IntData(Arrays.copyOf(out,count)));
      }
    }
  }
  void expressions(Map<String,Float> next){next=Map.copyOf(next);for(var e:next.entrySet())if(!profile.features().expressions().containsKey(e.getKey())||!Float.isFinite(e.getValue())||e.getValue()<0||e.getValue()>1)throw new IllegalArgumentException("Invalid semantic expression: "+e.getKey());
    if(next.equals(manual))return;
    var names=new HashSet<>(manual.keySet());names.addAll(next.keySet());
    for(String name:names)if(!Objects.equals(manual.get(name),next.get(name))) {
      var old=owners.get(name);float weight=old==null?next.getOrDefault(name,0f):old.weight(lastTime),alpha=old==null?0:old.alpha(lastTime);
      var expression=profile.features().expressions().get(name);boolean release=!next.containsKey(name);
      owners.put(name,new Owner(weight,release?weight:next.get(name),alpha,release?0:1,lastTime,release?expression.fadeOut():expression.fadeIn()));
    }
    manual=next;
  }
  private float strength(String name,double seconds){var owner=owners.get(name);return owner==null||owner.alpha(seconds)<=0?1:owner.alpha(seconds);}
  Map<String,Float> semantic(String authored,double seconds){return semantic(authored,seconds,seconds);}
  Map<String,Float> authored(String name,double seconds){return layerSemantics.apply(authoredBase(name,seconds));}
  Map<String,Float> authoredBase(String name,double seconds){var out=new LinkedHashMap<String,Float>();if(name!=null)for(var t:profile.features().animations().get(name).tracks())if(t.kind()==SceneModelFeatures.Target.EXPRESSION)out.put(t.binding(),CurveSampler.sample(t.curve(),seconds)[0]);return out;}
  Map<String,Float> semantic(String authored,double sourceSeconds,double seconds){return semantic(authored,sourceSeconds,seconds,Set.of());}
  Map<String,Float> semantic(String authored,double sourceSeconds,double seconds,Set<String> nativeExpressions){return semantic(authored,sourceSeconds,seconds,nativeExpressions,true);}
  Map<String,Float> semantic(String authored,double sourceSeconds,double seconds,Set<String> nativeExpressions,boolean layers){var result=new LinkedHashMap<String,Float>();
    if(authored!=null){var c=profile.features().animations().get(authored);for(var t:c.tracks())if(t.kind()==SceneModelFeatures.Target.EXPRESSION)result.put(t.binding(),CurveSampler.sample(t.curve(),sourceSeconds)[0]);}
    if(layers)result.putAll(layerSemantics.apply(result));
    if(faceAnimation!=null)for(var t:profile.features().animations().get(faceAnimation).tracks())result.put(t.binding(),CurveSampler.sample(t.curve(),faceSeconds)[0]);
    var p=profile.features().procedural();boolean blocked=result.containsKey(p.blink())||owners.containsKey(p.blink())&&owners.get(p.blink()).alpha(seconds)>0;
    for(var e:result.entrySet())if(e.getValue()>0&&profile.features().expressions().get(e.getKey()).blocks().contains(p.blink()))blocked=true;
    for(var e:owners.entrySet())if(e.getValue().alpha(seconds)>0&&profile.features().expressions().get(e.getKey()).blocks().contains(p.blink()))blocked=true;
    var blink=profile.features().expressions().get(p.blink());
    if(blink!=null&&blink.targets().stream().anyMatch(t->t.kind()==SceneModelFeatures.Target.VRM_EXPRESSION&&nativeExpressions.contains(t.name())))blocked=true;
    if(p.autoBlink()&&!blocked){
      // Deterministic per-profile phase. Seeking and multiple views produce the same result.
      double phase=((profile.profileId().hashCode()&0x7fffffff)/2147483648d)*p.interval();
      double at=(Math.max(0,seconds)+phase)%p.interval();if(at<p.duration())result.put(p.blink(),(float)Math.sin(at/p.duration()*Math.PI));
    }
    for(var e:owners.entrySet())if(e.getValue().alpha(seconds)>0)result.put(e.getKey(),e.getValue().weight(seconds));
    var suppressed=new HashSet<String>();for(var e:result.entrySet())if(e.getValue()>0)suppressed.addAll(profile.features().expressions().get(e.getKey()).blocks());suppressed.forEach(name->result.put(name,0f));return result;
  }
  AnimationFrame mmdFrame(AnimationFrame source,double seconds) {
    return mmdFrame(source,seconds,null,seconds);
  }
  AnimationFrame mmdFrame(AnimationFrame source,double seconds,String authored,double sourceSeconds) {
    var authoredWeights=mmd(authored(authored,sourceSeconds));if(!authoredWeights.isEmpty()){var channels=new ArrayList<>(source.channels());authoredWeights.forEach((i,w)->channels.add(new AnimationFrame.Channel(AnimationClip.Property.MORPH_WEIGHTS,i,"","",new FloatData(w))));source=new AnimationFrame(source.seconds(),channels);}
    var semantic=semantic(null,seconds);var desired=mmd(semantic);
    if(desired.isEmpty())return source;
    var base=new HashMap<Integer,Float>();for(var c:source.channels())if(c.property()==AnimationClip.Property.MORPH_WEIGHTS){int index=c.targetIndex();if(index<0){
      var modelNames=morphNames;Integer found=modelNames.get(c.binding());if(found!=null&&found>=0)index=found;
    }if(index>=0)base.put(index,c.value().get(0));}
    var mixed=new HashMap<Integer,Float>();semantic.forEach((name,value)->{for(var t:profile.features().expressions().get(name).targets())if(t.kind()==SceneModelFeatures.Target.MMD_MORPH){float from=base.getOrDefault(t.index(),0f);mixed.merge(t.index(),from+(value*t.weight()-from)*strength(name,seconds),Math::max);}});
    var channels=new ArrayList<>(source.channels());mixed.forEach((index,w)->channels.add(new AnimationFrame.Channel(AnimationClip.Property.MORPH_WEIGHTS,index,"","",new FloatData(w))));
    return new AnimationFrame(source.seconds(),channels);
  }
  final Map<String,Integer> morphNames=new HashMap<>();
  void morphName(String name,int index){if(!name.isEmpty())morphNames.merge(name,index,(a,b)->a.equals(b)?a:-1);}
  Map<Integer,Float> mmd(Map<String,Float> semantic){var result=new LinkedHashMap<Integer,Float>();targets(semantic,(t,w)->{if(t.kind()==SceneModelFeatures.Target.MMD_MORPH)result.merge(t.index(),w,Math::max);});return result;}
  Map<String,Float> vrm(Map<String,Float> semantic){var result=new LinkedHashMap<String,Float>();targets(semantic,(t,w)->{if(t.kind()==SceneModelFeatures.Target.VRM_EXPRESSION)result.merge(t.name(),w,Math::max);});return result;}
  Map<String,Float> vrm(Map<String,Float> semantic,double seconds,Map<String,Float> base){var desired=new HashMap<String,Float>();
    semantic.forEach((name,v)->{for(var t:profile.features().expressions().get(name).targets())if(t.kind()==SceneModelFeatures.Target.VRM_EXPRESSION){float from=base.getOrDefault(t.name(),0f);desired.merge(t.name(),from+(v*t.weight()-from)*strength(name,seconds),Math::max);}});
    var out=new LinkedHashMap<>(base);out.putAll(desired);
    var blocked=new HashSet<String>();
    for(var expression:profile.features().expressions().values())if(expression.targets().stream().anyMatch(t->t.kind()==SceneModelFeatures.Target.VRM_EXPRESSION&&out.getOrDefault(t.name(),0f)>0))blocked.addAll(expression.blocks());
    for(String name:blocked)for(var target:profile.features().expressions().get(name).targets())if(target.kind()==SceneModelFeatures.Target.VRM_EXPRESSION)out.put(target.name(),0f);
    return out;}
  private void targets(Map<String,Float> semantic,java.util.function.BiConsumer<SceneModelFeatures.ExpressionTarget,Float> out){semantic.forEach((name,value)->{
    var e=profile.features().expressions().get(name);if(e==null)throw new IllegalArgumentException("Unknown expression: "+name);for(var t:e.targets())out.accept(t,value*t.weight());});}
  ScenePose nodeMorphs(ScenePose pose,Map<String,Float> semantic,double seconds){return nodeMorphs(pose,semantic,seconds,true);}
  ScenePose nodeMorphs(ScenePose pose,Map<String,Float> semantic,double seconds,boolean ownership){var weights=new ArrayList<>(pose.morphWeights());var changed=new HashMap<Integer,float[]>();
    var desired=new HashMap<String,Float>();
    semantic.forEach((name,value)->{for(var t:profile.features().expressions().get(name).targets())if(t.kind()==SceneModelFeatures.Target.NODE_MORPH){String key=t.node()+":"+t.index();float from=pose.morphWeights().get(t.node()).get(t.index());desired.merge(key,from+(value*t.weight()-from)*(ownership?strength(name,seconds):1f),Math::max);}});
    desired.forEach((key,value)->{var parts=key.split(":");int node=Integer.parseInt(parts[0]),index=Integer.parseInt(parts[1]);var v=changed.computeIfAbsent(node,i->weights.get(i).copy());v[index]=value;});
    changed.forEach((i,v)->weights.set(i,new FloatData(v)));return new ScenePose(pose.seconds(),pose.localMatrices(),pose.globalMatrices(),weights,pose.extensionChannels());}
  Map<Integer,Rotation> eyes(){var p=profile.features().procedural();if(!p.eyeTracking())return Map.of();
    var q=SceneModelProfile.euler(Math.max(-p.maxEyeAngle(),Math.min(p.maxEyeAngle(),pitch)),Math.max(-p.maxEyeAngle(),Math.min(p.maxEyeAngle(),yaw)),0);
    var result=new LinkedHashMap<Integer,Rotation>();for(String role:List.of(p.leftEye(),p.rightEye()))if(!role.isEmpty())result.put(SceneFeatureBindings.requiredBone(profile,role),q);return result;}
  GeometryFrame firstPerson(GeometryFrame frame){var hidden=profile.features().firstPerson().hiddenMaterials();if(hidden.isEmpty()&&firstIndices.isEmpty())return frame;
    var draws=new ArrayList<GeometryFrame.Draw>();for(var draw:frame.draws()){
      // Keep the material index domain intact: empty index lists hide selected primitives.
      var primitives=draw.geometry().primitives().stream().map(p->hidden.contains(p.material())||firstIndices.containsKey(p.material())?new MeshAsset.Primitive(p.topology(),p.attributes(),hidden.contains(p.material())?IntData.EMPTY:firstIndices.get(p.material()),p.material(),p.skinning(),p.morphs()):p).toList();
      draws.add(new GeometryFrame.Draw(draw.node(),draw.mesh(),draw.world(),new MeshAsset(draw.geometry().name(),primitives,draw.geometry().defaultMorphWeights()),draw.visible(),draw.sourcePrimitives()));
    }return new GeometryFrame(frame.seconds(),frame.coordinates(),draws);}
  }
