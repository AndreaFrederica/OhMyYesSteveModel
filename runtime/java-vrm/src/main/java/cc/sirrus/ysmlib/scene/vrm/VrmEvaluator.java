package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.*;
import java.util.*;

public final class VrmEvaluator implements VrmEvaluation {
  private final VrmDocument document;
  private final SceneEvaluator scene;
  private final VrmGaze gaze;
  private final VrmConstraints constraints;
  private final VrmExpressionEvaluator expressions;
  private final List<Rotation> rest;
  public VrmEvaluator(VrmDocument document) {
    this.document=Objects.requireNonNull(document);scene=new SceneEvaluator(document.scene());gaze=new VrmGaze(document);
    constraints=new VrmConstraints(document.scene(),document.constraints());expressions=new VrmExpressionEvaluator(document);
    rest=document.scene().nodes().stream().map(VrmMath::restRotation).toList();
  }
  public Frame evaluate(AnimationClip clip,double seconds,Input input) {
    Objects.requireNonNull(input);var pose=scene.evaluate(clip,seconds);var rotations=new ArrayList<>(rest);
    if(clip!=null) for(var track:clip.tracks()) if(track.property()==AnimationClip.Property.ROTATION) {
      float[] q=CurveSampler.sample(track.curve(),seconds);rotations.set(track.targetIndex(),Rotation.normalized(q[0],q[1],q[2],q[3]));
    }
    return evaluatePose(pose,rotations,input);
  }
  Frame evaluatePose(ScenePose pose,List<Rotation> rotations,Input input) {
    var look=gaze.evaluate(pose,rotations,input.gaze());var weights=new LinkedHashMap<>(input.expressions());weights.putAll(look.expressions());
    var expression=expressions.evaluate(weights,input.categories());
    var morphs=new ArrayList<FloatData>();for(var w:pose.morphWeights()) morphs.add(new FloatData(w.copy()));
    // Expressions own their bound targets. Ordinary glTF animation on unbound morphs remains meaningful.
    var modified=new HashMap<Integer,float[]>();for(var e:document.expressions()) for(var b:e.morphs()) {
      float[] w=modified.computeIfAbsent(b.node(),n->morphs.get(n).copy());w[b.index()]=expression.morphWeights().get(b.node()).get(b.index());
    }
    modified.forEach((n,w)->morphs.set(n,new FloatData(w)));pose=look.pose();
    var constrained=constraints.evaluate(new ScenePose(pose.seconds(),pose.localMatrices(),pose.globalMatrices(),morphs,pose.extensionChannels()),look.rotations());
    return new Frame(constrained.pose(),constrained.localRotations(),expression.materials(),expression.effectiveWeights(),look.angles());
  }
}
