package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.CurveSampler;
import java.util.*;

/** Four explicit animation layers with RS sequential blending; independent of physics/render clocks. */
public final class MmdAnimationLayers {
  public enum State { STOPPED, PLAYING, PAUSED, FADING_IN, FADING_OUT, TRANSITIONING }
  public record Settings(float weight,float speed,boolean loop,double fadeInSeconds,double fadeOutSeconds) {
    public Settings {
      if(!Float.isFinite(weight) || weight<0 || weight>1 || !Float.isFinite(speed) || speed<0 ||
          !Double.isFinite(fadeInSeconds) || fadeInSeconds<0 || !Double.isFinite(fadeOutSeconds) || fadeOutSeconds<0)
        throw new IllegalArgumentException("Invalid animation layer settings");
    }
    public static Settings defaults(){return new Settings(1,1,true,0,0);}
  }
  public record Result(AnimationFrame animation,List<CompatibilityReport.Diagnostic> diagnostics) {
    public Result { diagnostics=List.copyOf(diagnostics); }
  }
  public interface Snapshot {}
  private static final class Layer {
    AnimationClip clip;
    Settings settings=Settings.defaults();
    State state=State.STOPPED,pausedState=State.PLAYING;
    double time,fade,transition,transitionDuration;
    float weight,fadeStart;
    boolean enabled=true;
    Set<Integer> mask,exclude=Set.of();
    AnimationFrame snapshot;
    Layer copy(){var n=new Layer();n.clip=clip;n.settings=settings;n.state=state;n.pausedState=pausedState;n.time=time;n.fade=fade;n.transition=transition;n.transitionDuration=transitionDuration;n.weight=weight;n.fadeStart=fadeStart;n.enabled=enabled;n.mask=mask;n.exclude=exclude;n.snapshot=snapshot;return n;}
    void restoreFrom(Layer n){clip=n.clip;settings=n.settings;state=n.state;pausedState=n.pausedState;time=n.time;fade=n.fade;transition=n.transition;transitionDuration=n.transitionDuration;weight=n.weight;fadeStart=n.fadeStart;enabled=n.enabled;mask=n.mask;exclude=n.exclude;snapshot=n.snapshot;}
  }
  private record Saved(Object owner,double seconds,List<Layer> layers) implements Snapshot {}
  private final Object owner=new Object();
  private final PmxDocument model;
  private final Map<String,List<Integer>> boneNames,morphNames;
  private final Map<Key,FloatData> defaults=new LinkedHashMap<>();
  private final Map<AnimationClip,CompiledClip> compiled=new IdentityHashMap<>();
  private final Map<AnimationClip,Double> durations=new IdentityHashMap<>();
  private final Layer[] layers={new Layer(),new Layer(),new Layer(),new Layer()};
  private double seconds;
  private Result cached;
  /** Single full-weight layer used by package playback. Keep sparse channels and authored order:
   * expanding defaults or blending duplicate tracks would change the player's override semantics.
   * Like frozen layer programs, bindings are compiled once and retained samples stay immutable.
   */
  public static MmdAnimationSource singleClip(PmxDocument model,AnimationClip clip,AnimationPlaybackRange range) {
    Objects.requireNonNull(model);Objects.requireNonNull(clip);
    boolean unboundBones=clip.tracks().stream().anyMatch(t->t.targetIndex()<0 && switch(t.property()) {
      case TRANSLATION,ROTATION,IK_ENABLED,BONE_PHYSICS_ENABLED -> true;default -> false;});
    boolean unboundMorphs=clip.tracks().stream().anyMatch(t->t.targetIndex()<0 && t.property()==AnimationClip.Property.MORPH_WEIGHTS);
    var bones=unboundBones?MmdRig.names(model.bones().stream().map(PmxDocument.Bone::names).toList()):Map.<String,List<Integer>>of();
    var morphs=unboundMorphs?MmdRig.names(model.morphs().stream().map(PmxDocument.Morph::names).toList()):Map.<String,List<Integer>>of();
    var tracks=new ArrayList<AnimationClip.Track>(clip.tracks().size());
    var constants=new ArrayList<AnimationFrame.Channel>(clip.tracks().size());
    boolean allConstant=true;
    for(var track:clip.tracks()) {
      Map<String,List<Integer>> names=switch(track.property()) {
        case TRANSLATION,ROTATION,IK_ENABLED,BONE_PHYSICS_ENABLED -> bones;
        case MORPH_WEIGHTS -> morphs;
        default -> Map.of();
      };
      var match=track.targetIndex()<0?names.get(track.binding()):null;
      // Leave missing/ambiguous names to the rig, preserving its original diagnostics.
      int index=match!=null&&match.size()==1?match.get(0):track.targetIndex();
      var bound=new AnimationClip.Track(track.property(),index,track.binding(),track.component(),track.curve());
      tracks.add(bound);
      var constant=track.curve().keys()==1?sample(bound,0):null;
      constants.add(constant);allConstant&=constant!=null;
    }
    final List<AnimationFrame.Channel> constantFrame=allConstant?List.copyOf(constants):null;
    return new MmdAnimationSource() {
      private Sample cached;
      @Override public synchronized Sample sample(double time) {
        finiteTime(time);double sourceTime=range==null?time:range.sample(time);
        if(cached!=null&&cached.animation().seconds()==sourceTime)return cached;
        List<AnimationFrame.Channel> channels=constantFrame;
        if(channels==null) {
          var values=new ArrayList<AnimationFrame.Channel>(tracks.size());
          for(int i=0;i<tracks.size();i++)values.add(constants.get(i)!=null?constants.get(i):MmdAnimationLayers.sample(tracks.get(i),sourceTime));
          channels=values;
        }
        cached=new Sample(new AnimationFrame(sourceTime,channels),List.of());return cached;
      }
    };
  }
  private static AnimationFrame.Channel sample(AnimationClip.Track track,double time) {
    return new AnimationFrame.Channel(track.property(),track.targetIndex(),track.binding(),track.component(),
        new FloatData(CurveSampler.sample(track.curve(),time)));
  }
  public MmdAnimationLayers(PmxDocument model) {
    this.model=Objects.requireNonNull(model);
    boneNames=MmdRig.names(model.bones().stream().map(PmxDocument.Bone::names).toList());
    morphNames=MmdRig.names(model.morphs().stream().map(PmxDocument.Morph::names).toList());
    initializeDefaults();
  }
  public MmdAnimationLayers(PmdDocument model){this(new PmdRuntimeProfile(model).asset);}
  private MmdAnimationLayers(PmxDocument model,Map<String,List<Integer>> boneNames,Map<String,List<Integer>> morphNames) {
    this.model=model;this.boneNames=boneNames;this.morphNames=morphNames;
    initializeDefaults();
  }
  private void initializeDefaults(){
    var zero=new FloatData(0,0,0);var identity=new FloatData(0,0,0,1);var off=new FloatData(0);
    for(int i=0;i<model.bones().size();i++){defaults.put(new Key(AnimationClip.Property.TRANSLATION,i,"",""),zero);defaults.put(new Key(AnimationClip.Property.ROTATION,i,"",""),identity);}
    for(int i=0;i<model.morphs().size();i++)defaults.put(new Key(AnimationClip.Property.MORPH_WEIGHTS,i,"",""),off);
  }
  /** Capture a time-pure program for fixed-step players and deterministic backward seeks.
   * Subsequent edits to this controller do not change the captured program.
   */
  public MmdAnimationSource freeze() {
    var captured=Arrays.stream(layers).map(Layer::copy).toArray(Layer[]::new);
    var source=model;var boneBindings=boneNames;var morphBindings=morphNames;
    var evaluation=new MmdAnimationLayers(source,boneBindings,morphBindings);
    return time->{
      finiteTime(time);
      synchronized(evaluation) {
        evaluation.seconds=0;evaluation.cached=null;
        for(int i=0;i<captured.length;i++)evaluation.layers[i].restoreFrom(captured[i]);
        evaluation.advance(time);var result=evaluation.current();
        return new MmdAnimationSource.Sample(result.animation(),result.diagnostics());
      }
    };
  }
  private Layer layer(int id){if(id<0 || id>=layers.length)throw new IllegalArgumentException("Layer index must be 0..3");return layers[id];}
  private static void finiteTime(double value){if(!Double.isFinite(value) || value<0)throw new IllegalArgumentException("Invalid animation time");}
  public void clip(int id,AnimationClip clip){var l=layer(id);l.clip=clip;compiled.clear();durations.clear();reset(id);}
  public void settings(int id,Settings settings){
    var l=layer(id);l.settings=Objects.requireNonNull(settings);var state=l.state==State.PAUSED?l.pausedState:l.state;
    if(state==State.FADING_IN)l.weight=(float)(settings.weight()*l.fade);
    else if(state==State.PLAYING || state==State.TRANSITIONING || state==State.STOPPED && l.weight>0)l.weight=settings.weight();
    cached=null;
  }
  public void enabled(int id,boolean enabled){layer(id).enabled=enabled;cached=null;}
  public void boneMask(int id,Set<Integer> include,Set<Integer> exclude) {
    var l=layer(id);var mask=include==null?null:Set.copyOf(include);var blocked=Set.copyOf(exclude);
    for(var set:Arrays.asList(mask,blocked))if(set!=null)for(int index:set)if(index<0 || index>=model.bones().size())throw new IllegalArgumentException("Invalid layer bone mask");
    l.mask=mask;l.exclude=blocked;cached=null;
  }
  public void play(int id) {
    var l=layer(id);if(l.clip==null)return;
    l.snapshot=null;l.fade=0;l.state=l.settings.fadeInSeconds()>0?State.FADING_IN:State.PLAYING;
    l.weight=l.state==State.FADING_IN?0:l.settings.weight();cached=null;
  }
  public void pause(int id){var l=layer(id);if(l.state!=State.STOPPED && l.state!=State.PAUSED){l.pausedState=l.state;l.state=State.PAUSED;cached=null;}}
  public void resume(int id){var l=layer(id);if(l.state==State.PAUSED){l.state=l.pausedState;cached=null;}}
  public void stop(int id) {
    var l=layer(id);if(l.settings.fadeOutSeconds()>0 && l.weight>0){l.state=State.FADING_OUT;l.fade=1;l.fadeStart=l.weight;l.snapshot=null;cached=null;}else reset(id);
  }
  public void reset(int id){var l=layer(id);l.time=0;l.weight=0;l.fade=0;l.transition=0;l.snapshot=null;l.state=State.STOPPED;cached=null;}
  public void seek(int id,double sourceSeconds){finiteTime(sourceSeconds);layer(id).time=sourceSeconds;cached=null;}
  public State state(int id){return layer(id).state;}
  public double sourceSeconds(int id){return layer(id).time;}
  public float effectiveWeight(int id){return layer(id).weight;}
  public boolean finished(int id){var l=layer(id);return l.clip==null || !l.settings.loop() && l.state==State.STOPPED && l.time>0;}
  public void transition(int id,AnimationClip clip,double durationSeconds) {
    finiteTime(durationSeconds);var l=layer(id);var previous=current().animation();
    l.clip=clip;l.time=0;l.snapshot=durationSeconds>0?previous:null;l.transition=0;l.transitionDuration=durationSeconds;
    compiled.clear();durations.clear();
    l.weight=clip==null?0:l.settings.weight();l.state=clip==null?State.STOPPED:durationSeconds>0?State.TRANSITIONING:State.PLAYING;cached=null;
  }
  public void advance(double elapsedSeconds) {
    finiteTime(elapsedSeconds);if(!Double.isFinite(seconds+elapsedSeconds))throw new IllegalArgumentException("Animation clock overflow");
    // Preflight every clock so one invalid late layer cannot advance earlier layers.
    for(var l:layers)if(l.enabled && l.clip!=null && l.state!=State.PAUSED && l.state!=State.STOPPED)
      if(!Double.isFinite(l.time+elapsedSeconds*l.settings.speed()))throw new IllegalArgumentException("Layer clock overflow");
    for(var l:layers) {
      if(!l.enabled || l.clip==null || l.state==State.PAUSED || l.state==State.STOPPED)continue;
      double dt=elapsedSeconds*l.settings.speed();
      if(l.state==State.FADING_IN){l.fade=Math.min(1,l.fade+(l.settings.fadeInSeconds()==0?1:dt/l.settings.fadeInSeconds()));l.weight=(float)(l.settings.weight()*l.fade);if(l.fade==1)l.state=State.PLAYING;}
      else if(l.state==State.FADING_OUT){l.fade=Math.max(0,l.fade-(l.settings.fadeOutSeconds()==0?1:dt/l.settings.fadeOutSeconds()));l.weight=(float)(l.fadeStart*l.fade);if(l.fade==0){l.time=0;l.state=State.STOPPED;}}
      else if(l.state==State.TRANSITIONING){l.transition=Math.min(1,l.transition+elapsedSeconds/l.transitionDuration);if(l.transition==1){l.snapshot=null;l.state=State.PLAYING;}}
      double duration=durations.computeIfAbsent(l.clip,AnimationClip::durationSeconds);
      if(duration>0 && l.state!=State.STOPPED) {
        l.time+=dt;
        if(l.time>duration){if(l.settings.loop())l.time%=duration;else{l.time=duration;l.state=State.STOPPED;l.snapshot=null;}}
      }
    }
    seconds+=elapsedSeconds;cached=null;
  }
  public Snapshot snapshot(){return new Saved(owner,seconds,Arrays.stream(layers).map(Layer::copy).toList());}
  public void restore(Snapshot snapshot) {
    if(!(snapshot instanceof Saved saved) || saved.owner()!=owner)throw new IllegalArgumentException("Foreign layer snapshot");
    var copies=saved.layers().stream().map(Layer::copy).toArray(Layer[]::new);
    System.arraycopy(copies,0,layers,0,layers.length);seconds=saved.seconds();cached=null;compiled.clear();durations.clear();
  }
  private record Key(AnimationClip.Property property,int index,String binding,String component) {}
  private record CompiledClip(List<Key> keys,List<CompatibilityReport.Diagnostic> diagnostics) {}
  private CompiledClip compile(AnimationClip clip){return compiled.computeIfAbsent(clip,c->{
    var diagnostics=new ArrayList<CompatibilityReport.Diagnostic>();var keys=new ArrayList<Key>();
    for(var track:c.tracks())keys.add(key(new AnimationFrame.Channel(track.property(),track.targetIndex(),track.binding(),track.component(),FloatData.EMPTY),diagnostics));
    return new CompiledClip(List.copyOf(keys),List.copyOf(diagnostics));
  });}
  private int bind(AnimationFrame.Channel c,Map<String,List<Integer>> names,int count,List<CompatibilityReport.Diagnostic> diagnostics) {
    if(c.targetIndex()>=0){if(c.targetIndex()>=count)throw new IllegalArgumentException("Layer target out of range");return c.targetIndex();}
    var match=names.get(c.binding());
    if(match==null || match.size()>1)diagnostics.add(new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.WARNING,c.binding(),match==null?"Unbound animation layer channel":"Ambiguous animation layer name; first source index selected"));
    return match==null?-1:match.get(0);
  }
  private Key key(AnimationFrame.Channel c,List<CompatibilityReport.Diagnostic> diagnostics) {
    int index=switch(c.property()) {
      case TRANSLATION,ROTATION,IK_ENABLED,BONE_PHYSICS_ENABLED -> bind(c,boneNames,model.bones().size(),diagnostics);
      case MORPH_WEIGHTS -> bind(c,morphNames,model.morphs().size(),diagnostics);
      default -> c.targetIndex();
    };
    return new Key(c.property(),index,index<0?c.binding():"",c.component());
  }
  private static FloatData identity(Key key,int width) {
    if(key.property()==AnimationClip.Property.ROTATION)return new FloatData(0,0,0,1);
    if(key.property()==AnimationClip.Property.IK_ENABLED || key.property()==AnimationClip.Property.BONE_PHYSICS_ENABLED || key.property()==AnimationClip.Property.DISPLAY)return new FloatData(1);
    return new FloatData(new float[width]);
  }
  private static FloatData blend(Key key,FloatData from,FloatData to,double weight) {
    if(from.size()!=to.size())throw new IllegalArgumentException("Layer channel width mismatch");
    if(key.property()==AnimationClip.Property.ROTATION) {
      if(to.size()!=4)throw new IllegalArgumentException("Invalid layer rotation");
      var a=Rotation.normalized(from.get(0),from.get(1),from.get(2),from.get(3));var b=Rotation.normalized(to.get(0),to.get(1),to.get(2),to.get(3));var q=Rotation.slerp(a,b,weight);return new FloatData(q.x(),q.y(),q.z(),q.w());
    }
    float[] values=new float[to.size()];for(int i=0;i<values.length;i++)values[i]=(float)(from.get(i)+((double)to.get(i)-from.get(i))*weight);return new FloatData(values);
  }
  private static boolean continuous(Key key){return key.property()==AnimationClip.Property.TRANSLATION || key.property()==AnimationClip.Property.ROTATION || key.property()==AnimationClip.Property.MORPH_WEIGHTS;}
  private static boolean masked(Layer l,Key key){return (key.property()==AnimationClip.Property.TRANSLATION || key.property()==AnimationClip.Property.ROTATION) && key.index()>=0 && (l.mask!=null && !l.mask.contains(key.index()) || l.exclude.contains(key.index()));}
  public Result current() {
    if(cached!=null)return cached;
    var values=new LinkedHashMap<Key,FloatData>(defaults);var diagnostics=new ArrayList<CompatibilityReport.Diagnostic>();
    for(var l:layers) {
      if(!l.enabled || l.clip==null || l.weight<=.001f)continue;
      var clip=compile(l.clip);diagnostics.addAll(clip.diagnostics());
      for(int i=0;i<l.clip.tracks().size();i++) {
        var key=clip.keys().get(i);if(masked(l,key))continue;
        var value=new FloatData(CurveSampler.sample(l.clip.tracks().get(i).curve(),l.time));
        if(continuous(key))values.put(key,blend(key,values.containsKey(key)?values.get(key):identity(key,value.size()),value,l.weight));
        else if(l.weight>=1)values.put(key,value);
      }
      if(l.snapshot!=null && (l.state==State.TRANSITIONING || l.state==State.PAUSED && l.pausedState==State.TRANSITIONING)) {
        double t=l.transition*l.transition*(3-2*l.transition);
        for(var c:l.snapshot.channels()) {
          var key=key(c,diagnostics);if(!continuous(key) || masked(l,key))continue;
          if(key.property()==AnimationClip.Property.MORPH_WEIGHTS && Math.abs(c.value().get(0))<=.001f)continue;
          values.put(key,blend(key,values.getOrDefault(key,identity(key,c.value().size())),c.value(),1-t));
        }
      }
    }
    var channels=new ArrayList<AnimationFrame.Channel>(values.size());values.forEach((key,value)->channels.add(new AnimationFrame.Channel(key.property(),key.index(),key.binding(),key.component(),value)));
    cached=new Result(new AnimationFrame(seconds,channels),diagnostics);return cached;
  }
}
