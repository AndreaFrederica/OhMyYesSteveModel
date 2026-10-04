package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Generic glTF-style TRS/morph evaluator. MMD constraints use their own scheduler and produce the same ScenePose. */
public final class SceneEvaluator implements SceneEvaluation {
  private final SceneAsset asset;
  private final int[] order,parents;
  public SceneEvaluator(SceneAsset asset) {
    this.asset=Objects.requireNonNull(asset);parents=new int[asset.nodes().size()];Arrays.fill(parents,-1);
    for(int n=0;n<parents.length;n++) for(int c:asset.nodes().get(n).children().copy()) parents[c]=n;
    var queue=new ArrayDeque<Integer>();for(int i=0;i<parents.length;i++) if(parents[i]==-1) queue.add(i);
    order=new int[parents.length];int i=0;while(!queue.isEmpty()) { int n=queue.remove();order[i++]=n;for(int c:asset.nodes().get(n).children().copy()) queue.add(c); }
    if(i!=parents.length) throw new IllegalArgumentException("Invalid scene hierarchy");
  }
  public ScenePose evaluate(AnimationClip clip,double seconds) {
    if(!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite scene time");
    int count=asset.nodes().size();var transforms=new Transform[count];var weights=new FloatData[count];var local=new Matrix4[count];var global=new Matrix4[count];
    for(int n=0;n<count;n++) {
      var node=asset.nodes().get(n);transforms[n]=node.transform();
      weights[n]=node.morphWeights().size()!=0?node.morphWeights():node.mesh()!=-1?asset.meshes().get(node.mesh()).defaultMorphWeights():FloatData.EMPTY;
      local[n]=node.localMatrix();
    }
    var extensions=new ArrayList<AnimationFrame.Channel>();var seen=new HashSet<String>();
    if(clip!=null) for(var track:clip.tracks()) {
      int n=track.targetIndex();float[] v=CurveSampler.sample(track.curve(),seconds);
      if(track.property()!=AnimationClip.Property.TRANSLATION && track.property()!=AnimationClip.Property.ROTATION
          && track.property()!=AnimationClip.Property.SCALE && track.property()!=AnimationClip.Property.MORPH_WEIGHTS) {
        extensions.add(new AnimationFrame.Channel(track.property(),n,track.binding(),track.component(),new FloatData(v)));continue;
      }
      if(n<0 || n>=count) throw new IllegalArgumentException("Animation track needs explicit node binding");
      if(!seen.add(n+":"+track.property())) throw new IllegalArgumentException("Duplicate animation channel");
      var t=transforms[n];
      if(track.property()==AnimationClip.Property.MORPH_WEIGHTS) {
        if(v.length!=weights[n].size()) throw new IllegalArgumentException("Animated morph count mismatch");weights[n]=new FloatData(v);continue;
      }
      if(asset.nodes().get(n).matrix()!=null) throw new IllegalArgumentException("TRS animation cannot target a matrix node");
      transforms[n]=switch(track.property()) {
        case TRANSLATION -> new Transform(vector(v),t.rotation(),t.scale());
        case ROTATION -> { if(v.length!=4) throw new IllegalArgumentException("Invalid rotation channel");yield new Transform(t.translation(),Rotation.normalized(v[0],v[1],v[2],v[3]),t.scale()); }
        case SCALE -> new Transform(t.translation(),t.rotation(),vector(v));
        default -> throw new AssertionError();
      };
      local[n]=transforms[n].matrix();
    }
    for(int n:order) global[n]=parents[n]==-1?local[n]:global[parents[n]].multiply(local[n]);
    return new ScenePose(seconds,Arrays.asList(local),Arrays.asList(global),Arrays.asList(weights),new AnimationFrame(seconds,extensions));
  }
  /** glTF palette maps bind-space vertex positions into the mesh node's local coordinates. */
  public List<Matrix4> skinPalette(ScenePose pose,int meshNode) {
    var node=asset.nodes().get(meshNode);if(node.skin()==-1) return List.of();var skin=asset.skins().get(node.skin());
    var inverseMesh=pose.globalMatrices().get(meshNode).inverse();var result=new ArrayList<Matrix4>(skin.joints().size());
    for(int i=0;i<skin.joints().size();i++) result.add(inverseMesh.multiply(pose.globalMatrices().get(skin.joints().get(i))).multiply(skin.inverseBindMatrices().get(i)));
    return List.copyOf(result);
  }
  private static Vec3 vector(float[] value) { if(value.length!=3) throw new IllegalArgumentException("Invalid vector channel");return new Vec3(value[0],value[1],value[2]); }
}
