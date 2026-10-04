package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.*;
import java.util.*;

/** Preserves normalized world orientation, including motion inherited through source-only optional bones. */
public final class VrmaRetarget implements VrmaEvaluation {
  private final VrmaDocument animation;
  private final VrmDocument target;
  private final SceneEvaluator sourceEvaluator,targetEvaluator;
  private final VrmEvaluator avatarEvaluator;
  private final ScenePose sourceRest,targetRest;
  private final int[] sourceParents,sourceOrder,targetParents,targetOrder,sourceByTarget;
  private final Rotation[] sourceRestLocal,sourceRestWorld,targetRestLocal,targetRestWorld;
  private final Rotation facing;
  private final double translationScale;
  private final Map<String,String> expressionBindings;
  private final CompatibilityReport report;
  public VrmaRetarget(VrmaDocument animation,VrmDocument target,Settings settings) {
    this.animation=Objects.requireNonNull(animation);this.target=Objects.requireNonNull(target);Objects.requireNonNull(settings);
    var sourceScene=animation.gltf().scene();sourceEvaluator=new SceneEvaluator(sourceScene);targetEvaluator=new SceneEvaluator(target.scene());avatarEvaluator=new VrmEvaluator(target);
    sourceRest=sourceEvaluator.evaluate(null,0);targetRest=targetEvaluator.evaluate(null,0);
    sourceParents=VrmTopology.parents(sourceScene);sourceOrder=VrmGaze.hierarchyOrder(sourceScene,sourceParents);targetParents=VrmTopology.parents(target.scene());targetOrder=VrmGaze.hierarchyOrder(target.scene(),targetParents);
    sourceRestLocal=sourceScene.nodes().stream().map(VrmMath::restRotation).toArray(Rotation[]::new);sourceRestWorld=globalRotations(sourceRestLocal,sourceParents,sourceOrder);
    targetRestLocal=target.scene().nodes().stream().map(VrmMath::restRotation).toArray(Rotation[]::new);targetRestWorld=globalRotations(targetRestLocal,targetParents,targetOrder);
    boolean legacy=target.version()==VrmDocument.Version.VRM_0;facing=legacy?Rotation.axisAngle(new Vec3(0,1,0),Math.PI):Rotation.IDENTITY;
    sourceByTarget=new int[targetParents.length];Arrays.fill(sourceByTarget,-1);
    for(var entry:target.humanBones().entrySet()) { Integer node=animation.humanBones().get(canonicalBone(entry.getKey(),legacy));if(node!=null) sourceByTarget[entry.getValue()]=node; }
    Integer hips=animation.humanBones().get("hips");boolean translates=hips!=null && animation.animations().stream().flatMap(c->c.tracks().stream()).anyMatch(t->t.targetIndex()==hips && t.property()==AnimationClip.Property.TRANSLATION);
    if(settings.hipsTranslationScale()!=null) translationScale=settings.hipsTranslationScale();
    else if(translates) {
      double src=VrmMath.position(sourceRest.globalMatrices().get(hips)).y(),dst=VrmMath.position(targetRest.globalMatrices().get(target.humanBones().get("hips"))).y();
      if(src<1e-6 || dst<1e-6) throw new IllegalArgumentException("Automatic retargeting needs positive rest hips heights; supply an explicit translation scale");translationScale=dst/src;
    } else translationScale=1;
    var binds=new LinkedHashMap<String,String>();var targetExpressions=new HashSet<String>();for(var e:target.expressions()) targetExpressions.add(e.key());
    for(String key:settings.expressionBindings().keySet()) if(!animation.expressions().containsKey(key)) throw new IllegalArgumentException("Unknown source expression mapping: "+key);
    var diagnostics=new ArrayList<CompatibilityReport.Diagnostic>();var features=new ArrayList<CompatibilityReport.Feature>();
    for(String key:animation.expressions().keySet()) {
      String mapped=settings.expressionBindings().getOrDefault(key,legacy?legacyExpression(key):key);
      if(targetExpressions.contains(mapped)) binds.put(key,mapped);
      else { if(settings.expressionBindings().containsKey(key)) throw new IllegalArgumentException("Explicit target expression does not exist: "+mapped);
        features.add(new CompatibilityReport.Feature("vrma.expression/"+key,CompatibilityReport.Level.UNSUPPORTED,true,"Target avatar has no corresponding expression"));diagnostics.add(new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.WARNING,key,"Source expression has no target binding; source animation remains available")); }
    }
    if(animation.lookAtNode()!=-1 && target.lookAt()==null) features.add(new CompatibilityReport.Feature("vrma.gaze",CompatibilityReport.Level.UNSUPPORTED,true,"Target avatar has no LookAt profile"));
    features.add(new CompatibilityReport.Feature("vrma.retarget",CompatibilityReport.Level.EVALUATED,true,"Rest-pose aware FK, optional-bone inheritance, hips scaling, expressions and extrinsic ZXY gaze"));
    // Unknown required glTF extensions retain their original coverage status.
    for(var f:animation.gltf().scene().compatibility().features()) if(!f.id().equals("VRMC_vrm_animation")) features.add(f);
    diagnostics.addAll(animation.gltf().scene().compatibility().diagnostics());expressionBindings=Map.copyOf(binds);report=new CompatibilityReport(features,diagnostics);
  }
  public CompatibilityReport compatibility() { return report; }
  public VrmEvaluation.Frame evaluate(int animationIndex,double seconds) {
    VrmJson.index(animationIndex,animation.animations().size(),"animation");var clip=animation.animations().get(animationIndex);var source=sourceEvaluator.evaluate(clip,seconds);
    Rotation[] sourceLocal=sourceRestLocal.clone();for(var track:clip.tracks()) if(track.property()==AnimationClip.Property.ROTATION) {
      float[] q=CurveSampler.sample(track.curve(),seconds);sourceLocal[track.targetIndex()]=Rotation.normalized(q[0],q[1],q[2],q[3]);
    }
    Rotation[] sourceWorld=globalRotations(sourceLocal,sourceParents,sourceOrder),local=targetRestLocal.clone(),world=new Rotation[targetParents.length];
    Matrix4[] matrices=targetRest.localMatrices().toArray(Matrix4[]::new),global=new Matrix4[targetParents.length];
    int targetHips=target.humanBones().get("hips");Integer sourceHips=animation.humanBones().get("hips");
    for(int n:targetOrder) {
      int p=targetParents[n],src=sourceByTarget[n];Rotation parent=p==-1?Rotation.IDENTITY:world[p];
      if(src!=-1) {
        Rotation delta=sourceWorld[src].multiply(sourceRestWorld[src].inverse());delta=facing.multiply(delta).multiply(facing.inverse());
        local[n]=parent.inverse().multiply(delta).multiply(targetRestWorld[n]);matrices[n]=VrmMath.rotate(matrices[n],targetRestLocal[n],local[n]);
      }
      if(n==targetHips && sourceHips!=null) {
        Vec3 displacement=VrmMath.position(source.globalMatrices().get(sourceHips)).subtract(VrmMath.position(sourceRest.globalMatrices().get(sourceHips)));
        Vec3 desired=VrmMath.position(targetRest.globalMatrices().get(n)).add(facing.rotate(displacement).multiply((float)translationScale));
        Vec3 position=p==-1?desired:global[p].inverse().transformPoint(desired);float[] data=matrices[n].copy();data[12]=position.x();data[13]=position.y();data[14]=position.z();matrices[n]=new Matrix4(data);
      }
      world[n]=parent.multiply(local[n]);global[n]=p==-1?matrices[n]:global[p].multiply(matrices[n]);
    }
    var expressions=new LinkedHashMap<String,Float>();for(var entry:expressionBindings.entrySet()) {
      int node=animation.expressions().get(entry.getKey());float value=VrmMath.position(source.localMatrices().get(node)).x();
      expressions.merge(entry.getValue(),Math.max(0,Math.min(1,value)),Float::sum);
    }
    VrmEvaluation.Angles gaze=animation.lookAtNode()==-1?null:angles(sourceLocal[animation.lookAtNode()]);
    var pose=new ScenePose(seconds,Arrays.asList(matrices),Arrays.asList(global),targetRest.morphWeights(),source.extensionChannels());
    return avatarEvaluator.evaluatePose(pose,Arrays.asList(local),new VrmEvaluation.Input(expressions,Map.of(),gaze));
  }
  static VrmEvaluation.Angles angles(Rotation q) {
    Matrix4 m=new Transform(Vec3.ZERO,q,Vec3.ONE).matrix();double x=Math.asin(Math.max(-1,Math.min(1,-m.get(2,1))));
    double y=Math.abs(m.get(2,1))<.9999999?Math.atan2(m.get(2,0),m.get(2,2)):Math.atan2(-m.get(0,2),m.get(0,0));
    return new VrmEvaluation.Angles(Math.toDegrees(y),Math.toDegrees(x));
  }
  private static Rotation[] globalRotations(Rotation[] local,int[] parents,int[] order) {
    Rotation[] global=new Rotation[local.length];for(int n:order) global[n]=parents[n]==-1?local[n]:global[parents[n]].multiply(local[n]);return global;
  }
  static String canonicalBone(String name,boolean legacy) {
    if(!legacy || !name.contains("Thumb")) return name;
    return name.endsWith("Proximal")?name.replace("Proximal","Metacarpal"):name.endsWith("Intermediate")?name.replace("Intermediate","Proximal"):name;
  }
  static String legacyExpression(String key) { return switch(key) {
    case "happy"->"joy";case "sad"->"sorrow";case "relaxed"->"fun";case "aa"->"a";case "ih"->"i";case "ou"->"u";case "ee"->"e";case "oh"->"o";
    case "blinkLeft"->"blink_l";case "blinkRight"->"blink_r";case "lookUp"->"lookup";case "lookDown"->"lookdown";case "lookLeft"->"lookleft";case "lookRight"->"lookright";default->key;
  }; }
}
