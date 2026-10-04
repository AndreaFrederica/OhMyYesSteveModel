package cc.sirrus.ysmlib.scene.vrm;

import java.util.Objects;

/** Backward seeks replay the same pure source; fractional frames never add a physics step. */
public final class VrmPlayer implements VrmPlayback {
  private final VrmDocument document;
  private final Source source;
  private final Settings settings;
  private VrmSpringSolver solver;
  private long steps;
  private VrmEvaluation.Frame frame;
  private final cc.sirrus.ysmlib.scene.physics.ScenePhysicsMotion hostMotion=new cc.sirrus.ysmlib.scene.physics.ScenePhysicsMotion();
  private cc.sirrus.ysmlib.scene.physics.PhysicsEnvironment environment;
  public synchronized void physicsInput(cc.sirrus.ysmlib.scene.ScenePhysicsInput input){
    physicsEnvironment(input==null?null:hostMotion.update(input));if(input==null)hostMotion.reset();
  }
  public synchronized void physicsEnvironment(cc.sirrus.ysmlib.scene.physics.PhysicsEnvironment input){
    environment=input;
    if(solver!=null)solver.environment(environment);
  }
  public VrmPlayer(VrmDocument document,Source source,Settings settings) {
    this.document=Objects.requireNonNull(document);this.source=Objects.requireNonNull(source);this.settings=Objects.requireNonNull(settings);
    var start=source.evaluate(0);frame=start;
    if(settings.physicsEnabled()) { solver=new VrmSpringSolver(document);solver.reset(start.pose(),start.localRotations());frame=combine(start,solver.sample(start.pose(),start.localRotations())); }
  }
  public synchronized VrmEvaluation.Frame seek(double seconds) {
    if(!Double.isFinite(seconds) || seconds<0) throw new IllegalArgumentException("Invalid preview time");
    if(!settings.physicsEnabled()) { frame=source.evaluate(seconds);return frame; }
    double count=Math.floor(seconds/settings.fixedStep()+1e-9);if(count>Long.MAX_VALUE) throw new IllegalArgumentException("Preview time overflow");long target=(long)count;
    boolean backward=seconds<frame.pose().seconds();long start=backward?0:steps;
    if(backward&&environment!=null)throw new IllegalStateException("Cannot replay historical host environment");
    // Live world frames have a bounded catch-up; independent preview keeps full deterministic replay.
    if(environment!=null)start=Math.max(start,target-8);
    if(target-start>settings.maximumStepsPerSeek()) throw new IllegalArgumentException("VRM replay exceeds work budget");
    VrmSpringSolver candidate=backward?new VrmSpringSolver(document):solver;var saved=backward?null:solver.snapshot();
    try {
      if(backward) { var initial=source.evaluate(0);candidate.reset(initial.pose(),initial.localRotations()); }
      for(long step=start+1;step<=target;step++) { var animated=source.evaluate(step*settings.fixedStep());candidate.step(animated.pose(),animated.localRotations(),settings.fixedStep()); }
      var animated=source.evaluate(seconds);var result=combine(animated,candidate.sample(animated.pose(),animated.localRotations()));
      solver=candidate;steps=target;frame=result;return frame;
    } catch(RuntimeException|Error e) { if(saved!=null) solver.restore(saved);throw e; }
  }
  private static VrmEvaluation.Frame combine(VrmEvaluation.Frame frame,NodeConstraintEvaluation.Frame pose) {
    return new VrmEvaluation.Frame(pose.pose(),pose.localRotations(),frame.materials(),frame.expressions(),frame.gaze());
  }
  public synchronized VrmEvaluation.Frame frame() { return frame; }
}
