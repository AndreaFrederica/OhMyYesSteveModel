package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;

/** Single-owner staged PMX rig. Deform order is authored layer/source order, not topologically rewritten. */
final class MmdRig {
  private static final int MAX_UPDATES=8_000_000;
  final PmxDocument source;
  final Pose[] world;
  final Vec3[] offset,translation;
  final Rotation[] rotation,ikRotation,localRotation;
  final boolean[] physicsEnabled;
  private final Vec3[] rest;
  private final int[] order;
  private final List<List<Integer>> children;
  private final Map<String,List<Integer>> boneNames,morphNames;
  private final boolean[] ikEnabled,physicsOverride;
  private final MmdMorphEvaluator morphEvaluator;
  private final List<CompatibilityReport.Diagnostic> diagnostics=new ArrayList<>();
  private Map<Integer,Pose> outside=Map.of();
  private AnimationFrame frame;
  private MmdMorphState morphs;
  private boolean visible;
  private int updates;
  private final List<PmdRuntimeProfile.Constraint> pmdConstraints;

  MmdRig(PmxDocument source) { this(source,null); }
  MmdRig(PmxDocument source,List<PmdRuntimeProfile.Constraint> pmdConstraints) {
    this.pmdConstraints=pmdConstraints;
    this.source=Objects.requireNonNull(source);int n=source.bones().size();
    world=new Pose[n];offset=new Vec3[n];translation=new Vec3[n];rotation=new Rotation[n];ikRotation=new Rotation[n];localRotation=new Rotation[n];rest=new Vec3[n];
    ikEnabled=new boolean[n];physicsOverride=new boolean[n];physicsEnabled=new boolean[n];children=new ArrayList<>();
    for(int i=0;i<n;i++) children.add(new ArrayList<>());
    for(int i=0;i<n;i++) {
      var bone=source.bones().get(i);int p=bone.parent();
      if(p>=n || p< -1 || p==i) throw new IllegalArgumentException("Invalid PMX parent");
      if(p>=0) children.get(p).add(i);
      rest[i]=p<0?bone.position():bone.position().subtract(source.bones().get(p).position());
      world[i]=new Pose(bone.position(),Rotation.IDENTITY);
    }
    order=java.util.stream.IntStream.range(0,n).boxed().sorted(Comparator
        .comparing((Integer i)->after(i)).thenComparingInt(i->source.bones().get(i).layer()).thenComparingInt(i->i)).mapToInt(i->i).toArray();
    boneNames=names(source.bones().stream().map(Bone::names).toList());morphNames=names(source.morphs().stream().map(Morph::names).toList());
    morphEvaluator=new MmdMorphEvaluator(source);
  }

  void begin(AnimationFrame frame,Map<Integer,Pose> outside) {
    if(!Double.isFinite(frame.seconds()) || frame.seconds()<0) throw new IllegalArgumentException("Invalid MMD time");
    this.frame=frame;this.outside=Map.copyOf(outside);updates=0;visible=true;diagnostics.clear();
    Arrays.fill(offset,Vec3.ZERO);Arrays.fill(rotation,Rotation.IDENTITY);Arrays.fill(ikRotation,Rotation.IDENTITY);
    Arrays.fill(ikEnabled,true);Arrays.fill(physicsOverride,false);Arrays.fill(physicsEnabled,true);float[] weights=new float[source.morphs().size()];
    for(var c:frame.channels()) {
      switch(c.property()) {
        case TRANSLATION,ROTATION,IK_ENABLED,BONE_PHYSICS_ENABLED -> {
          int i=bind(c,boneNames,world.length);if(i<0) continue;var v=c.value();
          if(c.property()==AnimationClip.Property.TRANSLATION) { width(c,3);offset[i]=new Vec3(v.get(0),v.get(1),v.get(2)); }
          else if(c.property()==AnimationClip.Property.ROTATION) { width(c,4);rotation[i]=Rotation.normalized(v.get(0),v.get(1),v.get(2),v.get(3)); }
          else if(c.property()==AnimationClip.Property.BONE_PHYSICS_ENABLED) { width(c,1);physicsEnabled[i]=v.get(0)!=0; }
          else { width(c,1);ikEnabled[i]=v.get(0)!=0; }
        }
        case MORPH_WEIGHTS -> { int i=bind(c,morphNames,weights.length);if(i>=0) { width(c,1);weights[i]=c.value().get(0); } }
        case DISPLAY -> { width(c,1);visible=c.value().get(0)!=0; }
        case SCALE,MATERIAL -> throw new IllegalArgumentException("MMD source animation does not define "+c.property());
        default -> { /* Camera, light and shadow remain available in the returned frame. */ }
      }
    }
    morphs=morphEvaluator.evaluate(new FloatData(weights));
    for(int i=0;i<world.length;i++) {
      var bone=source.bones().get(i);var fixed=bone.fixedAxis();
      if(fixed!=null) rotation[i]=fixedRotation(rotation[i],fixed);
      rotation[i]=morphs.boneRotations().get(i).multiply(rotation[i]);localRotation[i]=rotation[i];
      offset[i]=offset[i].add(morphs.boneTranslations().get(i));translation[i]=offset[i];
      if(bone.externalParentKey()!=null && !outside.containsKey(bone.externalParentKey()))
        diagnostic("bone["+i+"]","Outside parent key "+bone.externalParentKey()+" is unbound; identity delta is used");
    }
  }

  void update(boolean afterPhysics) {
    if(pmdConstraints!=null) {
      if(afterPhysics) return;
      var roots=new ArrayDeque<Integer>();for(int i=0;i<world.length;i++) if(source.bones().get(i).parent()<0) roots.add(i);
      while(!roots.isEmpty()) { int i=roots.remove();updateBone(i);roots.addAll(children.get(i)); }
      for(var task:pmdConstraints) if(ikEnabled[task.controller()]) new MmdIk(this).solve(task.controller(),task.ik());
      for(int i=0;i<world.length;i++) if(source.bones().get(i).inherit()!=null) updateSubtree(i);
      return;
    }
    for(int i:order) if(after(i)==afterPhysics) {
      updateBone(i);
      var constraint=source.bones().get(i).ik();
      if(constraint!=null && ikEnabled[i]) new MmdIk(this).solve(i,constraint);
    }
  }

  void updateBone(int i) {
    if(++updates>MAX_UPDATES) throw new IllegalArgumentException("MMD bone evaluation budget exceeded");
    if(physicsOverride[i]) return;
    var bone=source.bones().get(i);Vec3 t=offset[i];Rotation r=rotation[i];var inherit=bone.inherit();
    if(inherit!=null && inherit.parent()>=0) {
      int p=inherit.parent();boolean local=(bone.flags()&0x80)!=0;
      if((bone.flags()&0x100)!=0) {
        Rotation append=local?world[p].rotation():ikRotation[p].multiply(inheritedRotation(p));
        r=r.multiply(Rotation.slerp(Rotation.IDENTITY,append,inherit.weight()));
      }
      if((bone.flags()&0x200)!=0) {
        Vec3 append=local?world[p].position().subtract(source.bones().get(p).position()):translation[p];
        t=t.add(append.multiply(inherit.weight()));
      }
    }
    translation[i]=t;localRotation[i]=r;
    Pose local=new Pose(rest[i].add(t),ikRotation[i].multiply(r));
    Pose pose=bone.parent()<0?local:world[bone.parent()].multiply(local);
    if(bone.externalParentKey()!=null) pose=outside.getOrDefault(bone.externalParentKey(),Pose.IDENTITY).multiply(pose);
    world[i]=pose;
  }

  // Final append rotation includes the source bone's own animation, but excludes its IK correction.
  private Rotation inheritedRotation(int i) { return localRotation[i]; }
  boolean overridden(int i) { return physicsOverride[i]; }

  void updateSubtree(int i) {
    var pending=new ArrayDeque<Integer>();pending.push(i);
    while(!pending.isEmpty()) { int node=pending.pop();updateBone(node);for(int child:children.get(node)) pending.push(child); }
  }

  void applyPhysics(Map<Integer,Pose> transforms) {
    for(var entry:transforms.entrySet()) {
      int i=entry.getKey();if(i<0 || i>=world.length) throw new IllegalArgumentException("Invalid physics bone");
      physicsOverride[i]=true;world[i]=Objects.requireNonNull(entry.getValue());
    }
    // Only descendants are propagated; independent authoring layers retain their own evaluation stage.
    for(int root:transforms.keySet()) for(int child:children.get(root)) updateSubtree(child);
  }

  MmdPose pose() {
    var palette=new ArrayList<Matrix4>();
    for(int i=0;i<world.length;i++) palette.add(world[i].multiply(new Pose(source.bones().get(i).position().multiply(-1),Rotation.IDENTITY)).matrix());
    return new MmdPose(frame,visible,Arrays.asList(world.clone()),palette,morphs,diagnostics);
  }

  private boolean after(int i) { return (source.bones().get(i).flags()&0x1000)!=0; }
  private int bind(AnimationFrame.Channel c,Map<String,List<Integer>> names,int count) {
    if(c.targetIndex()>=0) { if(c.targetIndex()>=count) throw new IllegalArgumentException("Animation target out of range");return c.targetIndex(); }
    var matches=names.get(c.binding());
    if(matches==null) { diagnostic(c.binding(),"Unbound "+c.property()+" animation channel");return -1; }
    if(matches.size()>1) diagnostic(c.binding(),"Ambiguous animation name; first source index "+matches.get(0)+" selected from "+matches);
    return matches.get(0);
  }
  static Map<String,List<Integer>> names(List<Names> names) {
    var map=new LinkedHashMap<String,List<Integer>>();
    // Native names win over universal aliases, which only fill otherwise unbound names.
    for(int i=0;i<names.size();i++) map.computeIfAbsent(names.get(i).local(),ignored->new ArrayList<>()).add(i);
    var aliases=new LinkedHashMap<String,List<Integer>>();
    for(int i=0;i<names.size();i++) if(!names.get(i).universal().isEmpty()) aliases.computeIfAbsent(names.get(i).universal(),ignored->new ArrayList<>()).add(i);
    aliases.forEach(map::putIfAbsent);return map;
  }
  private void diagnostic(String location,String text) { diagnostics.add(new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.WARNING,location,text)); }
  private static void width(AnimationFrame.Channel c,int width) { if(c.value().size()!=width) throw new IllegalArgumentException("Invalid "+c.property()+" channel width"); }
  static Rotation fixedRotation(Rotation q,Vec3 axis) {
    if(axis.dot(axis)<1e-15) return Rotation.IDENTITY;
    double sign=q.w()<0?-1:1,angle=2*Math.acos(Math.min(1,Math.abs(q.w())));
    if(axis.dot(new Vec3(q.x(),q.y(),q.z()))*sign<0) angle=-angle;
    return Rotation.axisAngle(axis,angle);
  }
}
