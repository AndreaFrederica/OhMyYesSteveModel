package cc.sirrus.ysmlib.scene.vrm;

/** Fixed-step preview/live timeline. Its source must deterministically evaluate any requested nonnegative time. */
public interface VrmPlayback {
  @FunctionalInterface interface Source { VrmEvaluation.Frame evaluate(double seconds); }
  record Settings(double fixedStep,int maximumStepsPerSeek,boolean physicsEnabled) {
    public Settings(double fixedStep,int maximumStepsPerSeek) { this(fixedStep,maximumStepsPerSeek,true); }
    public Settings withPhysics(boolean enabled) { return new Settings(fixedStep,maximumStepsPerSeek,enabled); }
    public static Settings preview() { return new Settings(1.0/60,120_000); }
    public Settings { if(!Double.isFinite(fixedStep) || fixedStep<=0 || fixedStep>1 || maximumStepsPerSeek<1) throw new IllegalArgumentException("Invalid VRM playback settings"); }
  }
  VrmEvaluation.Frame seek(double seconds);
  VrmEvaluation.Frame frame();
  default void physicsInput(cc.sirrus.ysmlib.scene.ScenePhysicsInput input) { throw new UnsupportedOperationException("Host physics input unavailable"); }
  default void physicsEnvironment(cc.sirrus.ysmlib.scene.physics.PhysicsEnvironment environment) { throw new UnsupportedOperationException("Host physics environment unavailable"); }
}
