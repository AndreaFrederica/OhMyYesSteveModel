package cc.sirrus.ysmlib.scene;

import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.vrm.*;
import cc.sirrus.ysmlib.scene.fbx.*;
import java.util.*;

/** Single-owner format-preserving playback. Assets may be shared, mutable playback instances may not. */
public interface ScenePackagePlayback extends AutoCloseable {
  /** Empty sourceId selects the model's rest/default state. clip is a source-local clip or FBX stack index. */
  record Selection(String sourceId,int clip) {
    public static final Selection REST=new Selection("",-1);
    public Selection { Objects.requireNonNull(sourceId);if(clip< -1) throw new IllegalArgumentException("Invalid clip index"); }
  }
  record Settings(MmdPlayback.Settings mmd,VrmPlayback.Settings vrm,AnimationPlaybackRange animationRange,SceneModelProfile modelProfile,boolean deferMmdDeformation,boolean deferVrmDeformation) {
    public Settings(MmdPlayback.Settings mmd,VrmPlayback.Settings vrm,AnimationPlaybackRange range,SceneModelProfile profile,boolean deferMmd){this(mmd,vrm,range,profile,deferMmd,false);}
    public Settings(MmdPlayback.Settings mmd,VrmPlayback.Settings vrm,AnimationPlaybackRange animationRange,SceneModelProfile modelProfile) {this(mmd,vrm,animationRange,modelProfile,false);}
    public Settings(MmdPlayback.Settings mmd,VrmPlayback.Settings vrm,AnimationPlaybackRange range) { this(mmd,vrm,range,null); }
    public Settings(MmdPlayback.Settings mmd,VrmPlayback.Settings vrm) { this(mmd,vrm,null); }
    public Settings withAnimationRange(AnimationPlaybackRange range) { return new Settings(mmd,vrm,range,modelProfile,deferMmdDeformation,deferVrmDeformation); }
    public Settings withPhysics(boolean enabled) { return new Settings(mmd.withPhysics(enabled),vrm.withPhysics(enabled),animationRange,modelProfile,deferMmdDeformation,deferVrmDeformation); }
    public Settings withModelProfile(SceneModelProfile profile) { return new Settings(mmd,vrm,animationRange,profile,deferMmdDeformation,deferVrmDeformation); }
    public Settings withDeferredMmdDeformation(boolean enabled){return new Settings(mmd,vrm,animationRange,modelProfile,enabled,deferVrmDeformation);}
    public Settings withDeferredVrmDeformation(boolean enabled){return new Settings(mmd,vrm,animationRange,modelProfile,deferMmdDeformation,enabled);}
    public static Settings preview() { return new Settings(MmdPlayback.Settings.preview(),VrmPlayback.Settings.preview()); }
    public static Settings previewUi() { return new Settings(MmdPlayback.Settings.previewUi(),VrmPlayback.Settings.preview()); }
    public Settings { Objects.requireNonNull(mmd);Objects.requireNonNull(vrm); }
  }
  sealed interface Details permits Scene,Mmd,Vrm,Fbx {}
  record Scene(ScenePose pose) implements Details {}
  record Mmd(MmdPlayback.Frame value) implements Details {}
  record Vrm(VrmEvaluation.Frame value,VrmMaterials.Frame materials) implements Details {}
  record Fbx(FbxEvaluation.Frame value) implements Details {}
  /** Both views are derived from exactly the same animation/physics frame. Details retain light/camera/material data. */
  record Frame(double seconds,GeometryFrame thirdPerson,GeometryFrame firstPerson,Details details,Set<Integer> drivenBones,Map<Integer,Float> referenceWeights) {
    public Frame(double seconds,GeometryFrame thirdPerson,GeometryFrame firstPerson,Details details){this(seconds,thirdPerson,firstPerson,details,Set.of());}
    public Frame(double seconds,GeometryFrame thirdPerson,GeometryFrame firstPerson,Details details,Set<Integer> drivenBones){this(seconds,thirdPerson,firstPerson,details,drivenBones,fullReferences(drivenBones));}
    private static Map<Integer,Float> fullReferences(Set<Integer> bones){var weights=new HashMap<Integer,Float>();bones.forEach(b->weights.put(b,1f));return weights;}
    public Frame { drivenBones=Set.copyOf(drivenBones);referenceWeights=Map.copyOf(referenceWeights);for(var e:referenceWeights.entrySet())if(e.getKey()<0||!Float.isFinite(e.getValue())||e.getValue()<0||e.getValue()>1)throw new IllegalArgumentException("Invalid socket reference weight");Objects.requireNonNull(thirdPerson);Objects.requireNonNull(firstPerson);Objects.requireNonNull(details); }
  }
  ScenePackageAssets assets();
  Selection selection();
  Frame seek(double seconds);
  /** Returns true when the format can switch source without replacing its simulation clock/world. */
  default boolean select(Selection selection,AnimationPlaybackRange range){return false;}
  default boolean select(Selection selection,AnimationPlaybackRange range,double elapsed){return elapsed==0&&select(selection,range);}
  /** null detaches world input; independent previews retain source physics. */
  default void physicsInput(ScenePhysicsInput input) {}
  /** Hosts retaining a timeline across clip/player changes can own the derivatives themselves. */
  default void physicsEnvironment(cc.sirrus.ysmlib.scene.physics.PhysicsEnvironment environment) {}
  default void boneRotations(Map<Integer,Rotation> rotations) { if(!rotations.isEmpty()) throw new UnsupportedOperationException("Bone overlay unavailable for this format"); }
  /** Absolute local deltas from rest, after source animation and before MMD IK/physics. Empty releases ownership. */
  default void bonePoses(Map<Integer,Pose> poses) { if(!poses.isEmpty()) throw new UnsupportedOperationException("Bone pose overrides unavailable for this format"); }
  default void ikOverrides(Map<Integer,Boolean> switches) { if(!switches.isEmpty()) throw new UnsupportedOperationException("IK overrides unavailable for this format"); }
  /** Semantic face inputs; empty releases only this input's ownership, preserving source animation. */
  default void expressions(Map<String,Float> weights) {if(!weights.isEmpty())throw new UnsupportedOperationException("Expression inputs unavailable");}
  /** Independently sample an authored expression-only clip; null releases it without changing the body source. */
  default void expressionAnimation(String name,double seconds){if(name!=null)throw new UnsupportedOperationException("Expression animation unavailable");}
  /** Source-controller local deltas for explicit @ysm/controller layers, independent of body overrides. */
  default void sourcePoses(Map<Integer,Pose> poses){if(!poses.isEmpty())throw new UnsupportedOperationException("Source pose layer unavailable");}
  /** Degrees in face space, same convention as VRM. */
  default void gaze(double yaw,double pitch) {}
  /** Explicit source time and channel masks; overlays never implicitly own every morph. */
  record Layer(Selection selection,double seconds,float weight,Set<Integer> bones,Set<Integer> morphs,Set<String> expressions) {
    public Layer(Selection selection,double seconds,float weight,Set<Integer> bones,Set<Integer> morphs){this(selection,seconds,weight,bones,morphs,Set.of());}
    public Layer {Objects.requireNonNull(selection);bones=Set.copyOf(bones);morphs=Set.copyOf(morphs);expressions=Set.copyOf(expressions);
      if(!Double.isFinite(seconds)||seconds<0||!Float.isFinite(weight)||weight<0||weight>1)throw new IllegalArgumentException("Invalid playback layer");}
  }
  default void layers(List<Layer> layers){if(!layers.isEmpty())throw new UnsupportedOperationException("Animation layers unavailable");}
  @Override void close();
}
