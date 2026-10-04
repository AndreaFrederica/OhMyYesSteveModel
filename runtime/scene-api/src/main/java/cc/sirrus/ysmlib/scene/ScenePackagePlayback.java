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
  record Settings(MmdPlayback.Settings mmd,VrmPlayback.Settings vrm,AnimationPlaybackRange animationRange,SceneModelProfile modelProfile,boolean deferMmdDeformation) {
    public Settings(MmdPlayback.Settings mmd,VrmPlayback.Settings vrm,AnimationPlaybackRange animationRange,SceneModelProfile modelProfile) {this(mmd,vrm,animationRange,modelProfile,false);}
    public Settings(MmdPlayback.Settings mmd,VrmPlayback.Settings vrm,AnimationPlaybackRange range) { this(mmd,vrm,range,null); }
    public Settings(MmdPlayback.Settings mmd,VrmPlayback.Settings vrm) { this(mmd,vrm,null); }
    public Settings withAnimationRange(AnimationPlaybackRange range) { return new Settings(mmd,vrm,range,modelProfile,deferMmdDeformation); }
    public Settings withPhysics(boolean enabled) { return new Settings(mmd.withPhysics(enabled),vrm.withPhysics(enabled),animationRange,modelProfile,deferMmdDeformation); }
    public Settings withModelProfile(SceneModelProfile profile) { return new Settings(mmd,vrm,animationRange,profile,deferMmdDeformation); }
    public Settings withDeferredMmdDeformation(boolean enabled){return new Settings(mmd,vrm,animationRange,modelProfile,enabled);}
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
  record Frame(double seconds,GeometryFrame thirdPerson,GeometryFrame firstPerson,Details details) {
    public Frame { Objects.requireNonNull(thirdPerson);Objects.requireNonNull(firstPerson);Objects.requireNonNull(details); }
  }
  ScenePackageAssets assets();
  Selection selection();
  Frame seek(double seconds);
  default void boneRotations(Map<Integer,Rotation> rotations) { if(!rotations.isEmpty()) throw new UnsupportedOperationException("Bone overlay unavailable for this format"); }
  /** Absolute local deltas from rest, after source animation and before MMD IK/physics. Empty releases ownership. */
  default void bonePoses(Map<Integer,Pose> poses) { if(!poses.isEmpty()) throw new UnsupportedOperationException("Bone pose overrides unavailable for this format"); }
  default void ikOverrides(Map<Integer,Boolean> switches) { if(!switches.isEmpty()) throw new UnsupportedOperationException("IK overrides unavailable for this format"); }
  @Override void close();
}
