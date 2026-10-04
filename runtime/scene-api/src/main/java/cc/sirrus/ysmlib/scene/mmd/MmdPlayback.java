package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Single-owner preview/live session. Hosts own wall-clock, UI, GPU buffers and overall model placement. */
public interface MmdPlayback extends AutoCloseable {
  record Settings(int frequencyHz,int solverIterations,Vec3 gravity,boolean replayable,long maxStepsPerSeek,AnimationPlaybackRange animationRange,boolean physicsEnabled,boolean jointsEnabled,boolean kinematicFilter,boolean disableLinkedCollision,int maxLiveSubsteps) {
    public Settings(int frequencyHz,int solverIterations,Vec3 gravity,boolean replayable,long maxStepsPerSeek,AnimationPlaybackRange animationRange,boolean physicsEnabled,boolean jointsEnabled,boolean kinematicFilter,boolean disableLinkedCollision) {
      this(frequencyHz,solverIterations,gravity,replayable,maxStepsPerSeek,animationRange,physicsEnabled,jointsEnabled,kinematicFilter,disableLinkedCollision,8);
    }
    public Settings(int frequencyHz,int solverIterations,Vec3 gravity,boolean replayable,long maxStepsPerSeek,AnimationPlaybackRange animationRange,boolean physicsEnabled) {
      this(frequencyHz,solverIterations,gravity,replayable,maxStepsPerSeek,animationRange,physicsEnabled,true,false,false);
    }
    public Settings(int frequencyHz,int solverIterations,Vec3 gravity,boolean replayable,long maxStepsPerSeek,AnimationPlaybackRange animationRange) {
      this(frequencyHz,solverIterations,gravity,replayable,maxStepsPerSeek,animationRange,true);
    }
    public Settings(int frequencyHz,int solverIterations,Vec3 gravity,boolean replayable,long maxStepsPerSeek) {
      this(frequencyHz,solverIterations,gravity,replayable,maxStepsPerSeek,null);
    }
    public Settings withAnimationRange(AnimationPlaybackRange range) {
      return new Settings(frequencyHz,solverIterations,gravity,replayable,maxStepsPerSeek,range,physicsEnabled,jointsEnabled,kinematicFilter,disableLinkedCollision,maxLiveSubsteps);
    }
    public Settings withPhysics(boolean enabled) { return new Settings(frequencyHz,solverIterations,gravity,replayable,maxStepsPerSeek,animationRange,enabled,jointsEnabled,kinematicFilter,disableLinkedCollision,maxLiveSubsteps); }
    public Settings withMaxLiveSubsteps(int steps) { return new Settings(frequencyHz,solverIterations,gravity,replayable,maxStepsPerSeek,animationRange,physicsEnabled,jointsEnabled,kinematicFilter,disableLinkedCollision,steps); }
    /** Independent construction policy; RS defaults use all three switches enabled. */
    public Settings withPhysicsPolicy(boolean joints,boolean filter,boolean disableLinked) {
      return new Settings(frequencyHz,solverIterations,gravity,replayable,maxStepsPerSeek,animationRange,physicsEnabled,joints,filter,disableLinked,maxLiveSubsteps);
    }
    public Settings {
      Objects.requireNonNull(gravity);
      if(frequencyHz<1 || frequencyHz>1000 || solverIterations<1 || solverIterations>1000 || maxStepsPerSeek<1 || maxLiveSubsteps<1 || maxLiveSubsteps>1000)
        throw new IllegalArgumentException("Invalid MMD playback settings");
    }
    /** Source MMD units; gravity is 98 units/s². Spatial scale is applied by the host after simulation. */
    public static Settings preview() { return new Settings(60,10,new Vec3(0,-98,0),true,1_000_000); }
    /** Lower-cost catalog/Alt+Y transport. World playback continues to use preview(). */
    public static Settings previewUi() { return new Settings(30,4,new Vec3(0,-98,0),true,120_000); }
  }
  record Frame(double seconds,double simulationSeconds,MmdPose pose,List<Map<String,MeshAsset.Attribute>> primitives,boolean deformed) {
    public Frame(double seconds,double simulationSeconds,MmdPose pose,List<Map<String,MeshAsset.Attribute>> primitives){this(seconds,simulationSeconds,pose,primitives,true);}
    public Frame { primitives=primitives.stream().map(Map::copyOf).toList(); }
  }
  Frame current();
  /** Advance at fixed simulation steps and evaluate remaining fractional animation time without a solver step. */
  Frame advance(double elapsedSeconds);
  /** Rebuild and replay on backward seeks. A live session with replayable=false rejects backward seeks. */
  Frame seek(double seconds);
  default void boneRotations(Map<Integer,Rotation> rotations) { throw new UnsupportedOperationException("Bone overlays unavailable"); }
  /** Replace bound local translation/rotation channels, preserving unbound animation channels. */
  default void bonePoses(Map<Integer,Pose> poses) { throw new UnsupportedOperationException("Bone pose overrides unavailable"); }
  /** Absolute source VPD pose after animation layers; null clears the override. */
  default void vpdPose(VpdDocument pose) { throw new UnsupportedOperationException("VPD overlays unavailable"); }
  /** Explicit expression weights after VMD/VPD channels; an empty map releases them. */
  default void morphWeights(Map<Integer,Float> weights) { throw new UnsupportedOperationException("Expression overlays unavailable"); }
  /** Explicit IK switches after animated switches; an empty map restores animation ownership. */
  default void ikOverrides(Map<Integer,Boolean> switches) { throw new UnsupportedOperationException("IK overlays unavailable"); }
  @Override void close();
}
