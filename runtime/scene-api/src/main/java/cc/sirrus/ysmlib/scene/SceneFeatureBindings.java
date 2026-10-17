package cc.sirrus.ysmlib.scene;

import java.util.*;

/** Validates authored targets against the exact source document; never rebinds by a guessed name. */
public final class SceneFeatureBindings {
  private SceneFeatureBindings() {}
  public static void validate(ScenePackageAssets assets,SceneModelProfile profile) {
    var model=assets.model();var f=profile.features();
    for(var e:f.expressions().entrySet())for(var t:e.getValue().targets()) {
      String actual;
      if(t.kind()==SceneModelFeatures.Target.MMD_MORPH) {
        if(model instanceof ScenePackageAssets.Pmx p){index(t.index(),p.value().morphs().size(),"MMD morph");actual=p.value().morphs().get(t.index()).names().local();}
        else if(model instanceof ScenePackageAssets.Pmd p){index(t.index(),p.value().morphs().size(),"MMD morph");actual=p.value().morphs().get(t.index()).name();}
        else throw new IllegalArgumentException("MMD morph on another format: "+e.getKey());
      } else if(t.kind()==SceneModelFeatures.Target.VRM_EXPRESSION) {
        if(!(model instanceof ScenePackageAssets.Vrm v)||v.value().expressions().stream().noneMatch(x->x.key().equals(t.name())))
          throw new IllegalArgumentException("Missing VRM expression: "+t.name());
        actual=t.name();
      } else {
        var scene=scene(assets);if(scene==null)throw new IllegalArgumentException("Node morph requires a scene");
        index(t.node(),scene.nodes().size(),"Morph node");var node=scene.nodes().get(t.node());if(node.mesh()<0)throw new IllegalArgumentException("Morph node has no mesh");
        var mesh=scene.meshes().get(node.mesh());if(mesh.primitives().isEmpty())throw new IllegalArgumentException("Morph mesh is empty");
        index(t.index(),mesh.primitives().get(0).morphs().size(),"Node morph");actual=mesh.primitives().get(0).morphs().get(t.index()).name();
        for(var primitive:mesh.primitives())if(t.index()>=primitive.morphs().size()||!primitive.morphs().get(t.index()).name().equals(actual))throw new IllegalArgumentException("Inconsistent morph identity across primitives");
      }
      if(!actual.equals(t.name()))throw new IllegalArgumentException("Stale expression identity: "+e.getKey()+" / "+t.name());
    }
    boolean mmd=model instanceof ScenePackageAssets.Pmx||model instanceof ScenePackageAssets.Pmd;
    for(var e:f.expressions().values())for(String blocked:e.blocks())if(!f.expressions().containsKey(blocked))throw new IllegalArgumentException("Missing blocked expression: "+blocked);
    for(var c:f.animations().entrySet()){for(var t:c.getValue().tracks())if(t.kind()==SceneModelFeatures.Target.BONE)requiredBone(profile,t.binding());clip(profile,c.getKey(),mmd);}
    for(var r:f.itemRules()){requiredBone(profile,r.socket().binding());for(var part:r.parts())requiredBone(profile,part.socket().binding());}
    for(var r:f.layers()){r.bones().forEach(b->requiredBone(profile,b));for(String e:r.expressions())if(!f.expressions().containsKey(e))throw new IllegalArgumentException("Missing layer expression: "+e);
      if(r.animation().path().equals("@ysm/controller")){if(!(mmd||model instanceof ScenePackageAssets.Vrm)||profile.retarget().sourceBones().isEmpty()||!r.expressions().isEmpty())throw new IllegalArgumentException("YSM controller layer requires bound MMD or VRM bones");continue;}
      if(r.animation().path().startsWith("@ysm/source/")||r.animation().path().equals("REST")||r.animation().path().startsWith("@ysm/generated/"))throw new IllegalArgumentException("Layers require an authored or source clip");
      var selection=r.animation().resolve(assets.source());String id=selection.sourceId();if(id.startsWith("@ysm/profile/")){clip(profile,id.substring(13),mmd);}else{var d=assets.documents().get(id);
        if(!mmd&&!r.expressions().isEmpty()&&!(d instanceof ScenePackageAssets.Vrma))throw new IllegalArgumentException("Semantic scene layers require authored or VRMA expression tracks");
        if(d instanceof ScenePackageAssets.Vmd||d instanceof ScenePackageAssets.Vpd){if(!mmd||selection.clip()!=0)throw new IllegalArgumentException("Invalid MMD layer source");}
        else if(d instanceof ScenePackageAssets.Gltf g){if(!id.equals(assets.source().model().id()))throw new IllegalArgumentException("External scene layers require retargeting");index(selection.clip(),g.value().scene().animations().size(),"Layer clip");}
        else if(d instanceof ScenePackageAssets.Vrm v){if(!id.equals(assets.source().model().id()))throw new IllegalArgumentException("External scene layers require retargeting");index(selection.clip(),v.value().scene().animations().size(),"Layer clip");}
        else if(d instanceof ScenePackageAssets.Vrma v&&model instanceof ScenePackageAssets.Vrm){index(selection.clip(),v.value().animations().size(),"VRMA layer clip");}
        else throw new IllegalArgumentException("Unsupported layer animation: "+id);}}
    if(!f.sourceAnimations().isEmpty()&&profile.retarget().sourceBones().isEmpty())throw new IllegalArgumentException("YSM source actions need source bone bindings");
    if(f.procedural().autoBlink()&&!f.expressions().containsKey(f.procedural().blink()))throw new IllegalArgumentException("Auto blink has no expression mapping");
    for(String b:List.of(f.procedural().leftEye(),f.procedural().rightEye(),f.firstPerson().originBinding()))if(!b.isEmpty())requiredBone(profile,b);
    for(String bone:f.firstPerson().hiddenBones())requiredBone(profile,bone);
    if(!f.firstPerson().hiddenBones().isEmpty()&&!(model instanceof ScenePackageAssets.Pmx||model instanceof ScenePackageAssets.Pmd||model instanceof ScenePackageAssets.Vrm))throw new IllegalArgumentException("First-person bone filtering requires MMD or VRM");
    int materials=model instanceof ScenePackageAssets.Pmx p?p.value().materials().size():model instanceof ScenePackageAssets.Pmd p?p.value().materials().size():scene(assets)==null?0:scene(assets).materials().size();
    for(int m:f.firstPerson().hiddenMaterials())if(m>=materials)throw new IllegalArgumentException("First-person material out of range");
  }
  private static void index(int value,int count,String kind){if(value<0||value>=count)throw new IllegalArgumentException(kind+" index out of range: "+value);}
  public static int requiredBone(SceneModelProfile profile,String binding){var b=profile.bones().get(binding);if(b==null||b.index()<0)throw new IllegalArgumentException("Unbound feature bone: "+binding);return b.index();}
  public static SceneAsset scene(ScenePackageAssets assets){return assets.model() instanceof ScenePackageAssets.Gltf g?g.value().scene():assets.model() instanceof ScenePackageAssets.Vrm v?v.value().scene():null;}
  public static AnimationClip clip(SceneModelProfile profile,String name,boolean mmd) {
    return clip(profile,name,mmd,true);
  }
  public static AnimationClip clip(SceneModelProfile profile,String name,boolean mmd,boolean expressions) {
    var c=profile.features().animations().get(name);if(c==null)throw new IllegalArgumentException("Unknown authored animation: "+name);
    var tracks=new ArrayList<AnimationClip.Track>();
    for(var t:c.tracks()) {
      if(t.kind()==SceneModelFeatures.Target.BONE) {
        if(mmd&&t.property()==AnimationClip.Property.SCALE)throw new IllegalArgumentException("MMD bone scale cannot be expressed");
        tracks.add(new AnimationClip.Track(t.property(),requiredBone(profile,t.binding()),"","",t.curve()));
      } else if(mmd&&expressions)for(var target:profile.features().expressions().get(t.binding()).targets()) {
        if(target.kind()!=SceneModelFeatures.Target.MMD_MORPH)throw new IllegalArgumentException("Non-MMD authored expression");
        var keys=t.keys().stream().map(k->new SceneModelFeatures.Key(k.time(),List.of(k.value().get(0)*target.weight()))).toList();
        var scaled=new SceneModelFeatures.Track(t.kind(),t.binding(),t.property(),t.interpolation(),keys);
        tracks.add(new AnimationClip.Track(AnimationClip.Property.MORPH_WEIGHTS,target.index(),"","",scaled.curve()));
      }
    }
    return new AnimationClip(name,tracks,c.framesPerSecond());
  }
  public static List<SceneAnimation> animations(SceneModelProfile profile){var result=new ArrayList<SceneAnimation>();
    profile.features().animations().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->result.add(new SceneAnimation(
      new ScenePackagePlayback.Selection("@ysm/profile/"+e.getKey(),-1),e.getKey(),"@ysm/profile/"+e.getKey(),
      new AnimationPreview.Range(0,e.getValue().duration(),e.getValue().framesPerSecond()),e.getValue().framesPerSecond())));
    profile.features().sourceAnimations().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->result.add(new SceneAnimation(new ScenePackagePlayback.Selection("@ysm/source/"+e.getKey(),-1),e.getKey(),"@ysm/source/"+e.getKey(),new AnimationPreview.Range(0,e.getValue().duration(),e.getValue().framesPerSecond()),e.getValue().framesPerSecond())));return List.copyOf(result);}
}
