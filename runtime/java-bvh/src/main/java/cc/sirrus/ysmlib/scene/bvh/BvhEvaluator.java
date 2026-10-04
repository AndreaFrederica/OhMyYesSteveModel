package cc.sirrus.ysmlib.scene.bvh;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** BVH offset plus translation, with source-order Euler rotations and no quaternion key reduction. */
public final class BvhEvaluator implements BvhEvaluation {
  private final BvhDocument source;private final SceneAsset scene;
  public BvhEvaluator(BvhDocument source,SceneAsset.Coordinates coordinates) {
    this.source=Objects.requireNonNull(source);var nodes=new ArrayList<SceneAsset.Node>();var roots=new ArrayList<Integer>();
    var children=new ArrayList<List<Integer>>();for(int i=0;i<source.joints().size();i++) children.add(new ArrayList<>());
    for(int i=0;i<source.joints().size();i++) { int p=source.joints().get(i).parent();if(p<0) roots.add(i);else children.get(p).add(i); }
    for(int i=0;i<source.joints().size();i++) { var j=source.joints().get(i);nodes.add(new SceneAsset.Node(j.name(),new IntData(children.get(i).stream().mapToInt(n->n).toArray()),
        new Transform(j.offset(),Rotation.IDENTITY,new Vec3(1,1,1)),null,-1,-1,-1,-1,FloatData.EMPTY,Map.of("bvh.endSite",Boolean.toString(j.endSite())))); }
    var report=new CompatibilityReport(List.of(new CompatibilityReport.Feature("bvh.source-channels",CompatibilityReport.Level.EVALUATED,true,"Authored frame interval, channel order, offsets and End Sites")),List.of());
    scene=new SceneAsset("BVH",coordinates,nodes,List.of(new SceneAsset.Scene("BVH",new IntData(roots.stream().mapToInt(n->n).toArray()),Map.of())),0,
        List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of("bvh.coordinates","caller-supplied"),report);
  }
  @Override public SceneAsset scene() { return scene; }
  @Override public ScenePose evaluate(double seconds,Sampling sampling) {
    if(!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite BVH sample time");Objects.requireNonNull(sampling);
    double f=Math.max(0,Math.min(source.frames()-1,seconds/source.frameSeconds()));int a=(int)f,b=Math.min(a+1,source.frames()-1);
    double alpha=sampling==Sampling.STEP?0:f-a;var local=new ArrayList<Matrix4>();var global=new ArrayList<Matrix4>();
    for(var joint:source.joints()) {
      Vec3 translation=joint.offset();Rotation orientation=Rotation.IDENTITY;int channel=joint.channelOffset();
      if(source.frames()>0) for(var c:joint.channels()) {
        float x=source.samples().get(a*source.channels()+channel),y=source.samples().get(b*source.channels()+channel++),v=(float)(x+(y-(double)x)*alpha);
        Vec3 axis=switch(c) { case Xposition,Xrotation->new Vec3(1,0,0);case Yposition,Yrotation->new Vec3(0,1,0);case Zposition,Zrotation->new Vec3(0,0,1); };
        boolean rotation=c.ordinal()>=3;
        if(rotation) orientation=orientation.multiply(Rotation.axisAngle(axis,(float)Math.toRadians(v)));else translation=translation.add(axis.multiply(v));
      }
      Matrix4 matrix=new Pose(translation,orientation).matrix();local.add(matrix);global.add(joint.parent()<0?matrix:global.get(joint.parent()).multiply(matrix));
    }
    return new ScenePose(seconds,local,global,Collections.nCopies(local.size(),FloatData.EMPTY),new AnimationFrame(seconds,List.of()));
  }
}
