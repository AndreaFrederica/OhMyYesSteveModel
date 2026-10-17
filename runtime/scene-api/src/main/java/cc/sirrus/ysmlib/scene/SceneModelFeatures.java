package cc.sirrus.ysmlib.scene;

import java.util.*;

/** Portable authored face, clips and host interaction policy. No Minecraft objects or mutable clocks. */
public record SceneModelFeatures(Map<String,Expression> expressions,Map<String,Clip> animations,
    List<ItemRule> itemRules,List<LayerRule> layers,SocketSpace socketSpace,Procedural procedural,FirstPerson firstPerson,Map<String,SourceAnimation> sourceAnimations) {
  public SceneModelFeatures(Map<String,Expression> expressions,Map<String,Clip> animations,List<ItemRule> itemRules,List<LayerRule> layers,SocketSpace socketSpace,Procedural procedural,FirstPerson firstPerson){this(expressions,animations,itemRules,layers,socketSpace,procedural,firstPerson,Map.of());}
  public record SourceAnimation(String animation,double duration,double framesPerSecond){public SourceAnimation{id(animation);positive(duration);positive(framesPerSecond);}}
  public SceneModelFeatures {
    sourceAnimations=Map.copyOf(sourceAnimations);sourceAnimations.keySet().forEach(SceneModelFeatures::id);
    expressions=Map.copyOf(expressions);animations=Map.copyOf(animations);itemRules=List.copyOf(itemRules);layers=List.copyOf(layers);
    Objects.requireNonNull(socketSpace);Objects.requireNonNull(procedural);Objects.requireNonNull(firstPerson);
    expressions.keySet().forEach(SceneModelFeatures::id);animations.keySet().forEach(SceneModelFeatures::id);
    var names=new HashSet<String>();for(var r:itemRules)if(!names.add(r.id()))throw new IllegalArgumentException("Duplicate item rule: "+r.id());
    names.clear();for(var r:layers)if(!names.add(r.id()))throw new IllegalArgumentException("Duplicate layer: "+r.id());
    for(var clip:animations.values())for(var t:clip.tracks())if(t.kind()==Target.EXPRESSION&&!expressions.containsKey(t.binding()))
      throw new IllegalArgumentException("Missing expression: "+t.binding());
  }
  public static SceneModelFeatures empty(){return new SceneModelFeatures(Map.of(),Map.of(),List.of(),List.of(),SocketSpace.BONE,Procedural.defaults(),FirstPerson.defaults());}
  public enum SocketSpace { BONE, RETARGET_REFERENCE }
  public enum Target { BONE, MMD_MORPH, NODE_MORPH, VRM_EXPRESSION, EXPRESSION }
  /** For MMD index+name is source identity. NODE_MORPH additionally identifies the owning node. */
  public record ExpressionTarget(Target kind,int node,int index,String name,float weight) {
    public ExpressionTarget {Objects.requireNonNull(kind);Objects.requireNonNull(name);unit(weight);
      if(kind!=Target.MMD_MORPH&&kind!=Target.NODE_MORPH&&kind!=Target.VRM_EXPRESSION)throw new IllegalArgumentException("Invalid expression target");
      if(kind==Target.VRM_EXPRESSION){id(name);if(index!=-1||node!=-1)throw new IllegalArgumentException("VRM expressions use keys");}
      else if(index<0||name.isBlank()||kind==Target.NODE_MORPH&&node<0||kind==Target.MMD_MORPH&&node!=-1)throw new IllegalArgumentException("Invalid morph identity");}
  }
  public record Expression(List<ExpressionTarget> targets,List<String> blocks,double fadeIn,double fadeOut) {
    public Expression {targets=List.copyOf(targets);blocks=List.copyOf(blocks);if(targets.isEmpty())throw new IllegalArgumentException("Empty expression");
      var identities=new HashSet<String>();for(var t:targets)if(!identities.add(t.kind()+":"+t.node()+":"+t.index()+":"+t.name()))throw new IllegalArgumentException("Duplicate expression target");
      blocks.forEach(SceneModelFeatures::id);nonnegative(fadeIn);nonnegative(fadeOut);}
  }
  public enum Interpolation { STEP, LINEAR }
  /** BONE binding is a profile bone key. EXPRESSION binding is a semantic expression key. */
  public record Track(Target kind,String binding,AnimationClip.Property property,Interpolation interpolation,
      List<Key> keys) {
    public Track {Objects.requireNonNull(kind);id(binding);Objects.requireNonNull(property);Objects.requireNonNull(interpolation);keys=List.copyOf(keys);
      if(kind!=Target.BONE&&kind!=Target.EXPRESSION)throw new IllegalArgumentException("Authored tracks require BONE or EXPRESSION");
      int width=kind==Target.EXPRESSION?1:switch(property){case TRANSLATION,SCALE->3;case ROTATION->4;case IK_ENABLED->1;default->throw new IllegalArgumentException("Unsupported authored bone channel");};
      if(kind==Target.EXPRESSION&&property!=AnimationClip.Property.MORPH_WEIGHTS)throw new IllegalArgumentException("Expression track must use MORPH_WEIGHTS");
      if(keys.isEmpty())throw new IllegalArgumentException("Empty curve");double previous=-1;
      for(var k:keys){if(k.time()<=previous||k.value().size()!=width)throw new IllegalArgumentException("Invalid curve shape/time");previous=k.time();
        if(kind==Target.EXPRESSION)unit(k.value().get(0));if(property==AnimationClip.Property.IK_ENABLED&&k.value().get(0)!=0&&k.value().get(0)!=1)throw new IllegalArgumentException("IK keys must be zero or one");
        if(property==AnimationClip.Property.ROTATION){double norm=0;for(float v:k.value())norm+=v*v;if(norm<1e-20)throw new IllegalArgumentException("Zero rotation");}}
    }
    public AnimationCurve curve(){var times=new double[keys.size()];var values=new float[keys.size()*keys.get(0).value().size()];int at=0;
      for(int i=0;i<keys.size();i++){times[i]=keys.get(i).time();for(float v:keys.get(i).value())values[at++]=v;}
      return new AnimationCurve(times,new FloatData(values),keys.get(0).value().size(),property==AnimationClip.Property.ROTATION,
          AnimationCurve.Interpolation.valueOf(interpolation.name()),FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY);}
  }
  public record Key(double time,List<Float> value) {public Key {nonnegative(time);value=List.copyOf(value);for(float f:value)finite(f);}}
  public record Clip(double duration,double framesPerSecond,List<Track> tracks) {
    public Clip {positive(duration);positive(framesPerSecond);tracks=List.copyOf(tracks);if(tracks.isEmpty())throw new IllegalArgumentException("Empty authored animation");
      var channels=new HashSet<String>();for(var t:tracks)if(!channels.add(t.kind()+":"+t.binding()+":"+t.property()))throw new IllegalArgumentException("Duplicate authored channel");
      for(var t:tracks)if(t.keys().get(t.keys().size()-1).time()>duration)throw new IllegalArgumentException("Key after clip end");}
  }
  public enum Hand { ANY, LEFT, RIGHT, MAIN, OFF }
  public enum View { ANY, FIRST_PERSON, THIRD_PERSON }
  public enum ItemState { ANY, IDLE, USING, SWINGING, CHARGED }
  public enum Display { INHERIT, FIRST_PERSON, THIRD_PERSON, FIXED, NONE }
  public record Match(List<String> items,List<String> tags,List<String> categories,Hand hand,View view,ItemState state,String useAction,List<String> blocks,List<String> blockTags) {
    public Match(List<String> items,List<String> tags,List<String> categories,Hand hand,View view,ItemState state,String useAction){this(items,tags,categories,hand,view,state,useAction,List.of(),List.of());}
    public Match {items=List.copyOf(items);tags=List.copyOf(tags);blocks=List.copyOf(blocks);blockTags=List.copyOf(blockTags);categories=List.copyOf(categories);categories.forEach(SceneModelFeatures::id);Objects.requireNonNull(hand);Objects.requireNonNull(view);Objects.requireNonNull(state);Objects.requireNonNull(useAction);
      for(String v:items)resource(v);for(String v:tags)resource(v);for(String v:blocks)resource(v);for(String v:blockTags)resource(v);}
    public boolean matches(ItemContext c){return (items.isEmpty()||items.contains(c.item()))&&(tags.isEmpty()||tags.stream().anyMatch(c.tags()::contains))
      &&(categories.isEmpty()||categories.stream().anyMatch(c.categories()::contains))
      &&(blocks.isEmpty()||blocks.contains(c.block()))&&(blockTags.isEmpty()||blockTags.stream().anyMatch(c.blockTags()::contains))
      &&switch(hand){case ANY->true;case LEFT->c.left();case RIGHT->!c.left();case MAIN->c.main();case OFF->!c.main();}
      &&(view==View.ANY||(view==View.FIRST_PERSON)==c.firstPerson())&&(state==ItemState.ANY||state==c.state())
      &&(useAction.isEmpty()||useAction.equals(c.useAction()));}
  }
  public record ItemContext(String item,Set<String> tags,Set<String> categories,boolean left,boolean main,boolean firstPerson,ItemState state,String useAction,String block,Set<String> blockTags) {
    public ItemContext(String item,Set<String> tags,Set<String> categories,boolean left,boolean main,boolean firstPerson,ItemState state,String useAction){this(item,tags,categories,left,main,firstPerson,state,useAction,"",Set.of());}
    public ItemContext {Objects.requireNonNull(item);Objects.requireNonNull(block);tags=Set.copyOf(tags);blockTags=Set.copyOf(blockTags);categories=Set.copyOf(categories);Objects.requireNonNull(state);Objects.requireNonNull(useAction);}
  }
  /** Highest priority wins; equal priorities use the last matching rule. Socket fully replaces the hand default. */
  public record ItemRule(String id,int priority,Match match,SceneModelProfile.HandSocket socket,Display display,String renderer,List<ItemPart> parts) {
    public ItemRule(String id,int priority,Match match,SceneModelProfile.HandSocket socket,Display display,String renderer){this(id,priority,match,socket,display,renderer,List.of());}
    public ItemRule {SceneModelFeatures.id(id);Objects.requireNonNull(match);Objects.requireNonNull(socket);Objects.requireNonNull(display);SceneModelFeatures.id(renderer);parts=List.copyOf(parts);}
  }
  public record ItemPart(SceneModelProfile.HandSocket socket,Display display,String renderer){public ItemPart{Objects.requireNonNull(socket);Objects.requireNonNull(display);id(renderer);}}
  public record Attachment(SceneModelProfile.HandSocket socket,Display display,String renderer,List<ItemPart> parts) {public Attachment(SceneModelProfile.HandSocket socket,Display display,String renderer){this(socket,display,renderer,List.of());}}
  public Attachment attachment(SceneModelProfile profile,ItemContext context){var result=new Attachment(context.left()?profile.heldItems().left():profile.heldItems().right(),Display.INHERIT,"vanilla");int best=Integer.MIN_VALUE;
    for(var r:itemRules)if(r.priority()>=best&&r.match().matches(context)){best=r.priority();result=new Attachment(r.socket(),r.display(),r.renderer(),r.parts());}return result;}
  /** Morph channels are opt-in; an arm mask never implicitly owns the face. */
  public enum LayerMode { ANY, SOURCE_CLIP, YSM_CONTROLLER }
  public record LayerRule(String id,int priority,Match match,SceneModelProfile.Action animation,List<String> bones,
      List<String> expressions,float weight,float speed,double fadeIn,double fadeOut,LayerMode mode) {
    public LayerRule(String id,int priority,Match match,SceneModelProfile.Action animation,List<String> bones,List<String> expressions,float weight,float speed,double fadeIn,double fadeOut){this(id,priority,match,animation,bones,expressions,weight,speed,fadeIn,fadeOut,LayerMode.SOURCE_CLIP);}
    public LayerRule {SceneModelFeatures.id(id);Objects.requireNonNull(mode);Objects.requireNonNull(match);Objects.requireNonNull(animation);bones=List.copyOf(bones);expressions=List.copyOf(expressions);
      bones.forEach(SceneModelProfile::checkBindingKey);expressions.forEach(SceneModelFeatures::id);unit(weight);positive(speed);nonnegative(fadeIn);nonnegative(fadeOut);
      if(bones.isEmpty()&&expressions.isEmpty())throw new IllegalArgumentException("Layer requires explicit channels");}
  }
  public record Procedural(boolean autoBlink,String blink,double interval,double duration,boolean eyeTracking,
      String leftEye,String rightEye,double maxEyeAngle) {
    public Procedural {id(blink);positive(interval);positive(duration);if(duration>=interval)throw new IllegalArgumentException("Blink duration exceeds interval");
      Objects.requireNonNull(leftEye);Objects.requireNonNull(rightEye);if(!leftEye.isEmpty())SceneModelProfile.checkBindingKey(leftEye);if(!rightEye.isEmpty())SceneModelProfile.checkBindingKey(rightEye);positive(maxEyeAngle);}
    public static Procedural defaults(){return new Procedural(false,"blink",4,.15,false,"","",20);}
  }
  /** Explicit source material exclusions avoid unreliable automatic head geometry guesses. */
  public record FirstPerson(List<Integer> hiddenMaterials,String originBinding,double x,double y,double z,List<String> hiddenBones,float boneThreshold,
      double cameraX,double cameraY,double cameraZ,double originX,double originY,double originZ) {
    public FirstPerson(List<Integer> hiddenMaterials,String originBinding,double x,double y,double z){this(hiddenMaterials,originBinding,x,y,z,List.of(),.5f);}
    public FirstPerson(List<Integer> hiddenMaterials,String originBinding,double x,double y,double z,List<String> hiddenBones,float boneThreshold){this(hiddenMaterials,originBinding,x,y,z,hiddenBones,boneThreshold,0,0,0);}
    public FirstPerson(List<Integer> hiddenMaterials,String originBinding,double x,double y,double z,List<String> hiddenBones,float boneThreshold,double cameraX,double cameraY,double cameraZ){this(hiddenMaterials,originBinding,x,y,z,hiddenBones,boneThreshold,cameraX,cameraY,cameraZ,0,0,0);}
    public FirstPerson {hiddenBones=List.copyOf(hiddenBones);hiddenBones.forEach(SceneModelProfile::checkBindingKey);unit(boneThreshold);if(boneThreshold==0)throw new IllegalArgumentException("Zero first-person threshold");hiddenMaterials=List.copyOf(hiddenMaterials);for(int m:hiddenMaterials)if(m<0)throw new IllegalArgumentException("Invalid hidden material");
      Objects.requireNonNull(originBinding);if(!originBinding.isEmpty())SceneModelProfile.checkBindingKey(originBinding);finite(x);finite(y);finite(z);finite(cameraX);finite(cameraY);finite(cameraZ);finite(originX);finite(originY);finite(originZ);}
    public static FirstPerson defaults(){return new FirstPerson(List.of(),"",0,0,0);}
  }
  private static void resource(String s){if(s==null||!s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Expected resource ID: "+s);}
  private static void id(String s){if(s==null||s.isBlank()||s.length()>128)throw new IllegalArgumentException("Invalid feature key");}
  private static void finite(double v){if(!Double.isFinite(v))throw new IllegalArgumentException("Nonfinite feature value");}
  private static void nonnegative(double v){finite(v);if(v<0)throw new IllegalArgumentException("Negative feature value");}
  private static void positive(double v){finite(v);if(v<=0)throw new IllegalArgumentException("Expected positive feature value");}
  private static void unit(float v){finite(v);if(v<0||v>1)throw new IllegalArgumentException("Weight outside [0,1]");}
}
