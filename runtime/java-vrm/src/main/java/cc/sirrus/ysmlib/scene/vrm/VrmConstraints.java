package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmDocument.*;

/** A dependency graph distinguishes local rotation reads from global transform reads. No recursive traversal. */
public final class VrmConstraints implements NodeConstraintEvaluation {
  private final int[] parents,order;
  private final Constraint[] constraints;
  private final Rotation[] rest;
  public VrmConstraints(SceneAsset scene,List<Constraint> input) {
    int count=scene.nodes().size();parents=VrmTopology.parents(scene);constraints=new Constraint[count];rest=new Rotation[count];
    Arrays.fill(rest,Rotation.IDENTITY);
    for(var c:input) {
      VrmJson.index(c.node(),count,"constraint destination");VrmJson.index(c.source(),count,"constraint source");
      if(c.node()==c.source() || constraints[c.node()]!=null) throw new IllegalArgumentException("Invalid or duplicate constraint");
      VrmJson.range(c.weight(),0,1,"constraint weight");constraints[c.node()]=c;
      rest[c.node()]=VrmMath.restRotation(scene.nodes().get(c.node()));
      if(c.type()!=ConstraintType.AIM) rest[c.source()]=VrmMath.restRotation(scene.nodes().get(c.source()));
    }
    var edges=new ArrayList<List<Integer>>(count*2);for(int n=0;n<count*2;n++) edges.add(new ArrayList<>());int[] indegree=new int[count*2];
    for(int n=0;n<count;n++) {
      edge(edges,indegree,n,n+count);if(parents[n]!=-1) edge(edges,indegree,parents[n]+count,n+count);
      var c=constraints[n];if(c==null) continue;
      if(c.type()==ConstraintType.AIM) {
        edge(edges,indegree,c.source()+count,n);if(parents[n]!=-1) edge(edges,indegree,parents[n]+count,n);
      } else edge(edges,indegree,c.source(),n);
    }
    var queue=new ArrayDeque<Integer>();for(int n=0;n<indegree.length;n++) if(indegree[n]==0) queue.add(n);
    order=new int[count*2];int i=0;while(!queue.isEmpty()) { int n=queue.remove();order[i++]=n;for(int d:edges.get(n)) if(--indegree[d]==0) queue.add(d); }
    if(i!=order.length) throw new IllegalArgumentException("Circular node constraint dependency (including ancestor transforms)");
  }
  private static void edge(List<List<Integer>> edges,int[] indegree,int from,int to) { edges.get(from).add(to);indegree[to]++; }
  public Frame evaluate(ScenePose input,List<Rotation> localRotations) {
    int count=parents.length;if(localRotations.size()!=count || input.localMatrices().size()!=count) throw new IllegalArgumentException("Constraint pose size mismatch");
    Matrix4[] local=input.localMatrices().toArray(Matrix4[]::new),global=new Matrix4[count];
    Rotation[] rotation=localRotations.toArray(Rotation[]::new),worldRotation=new Rotation[count];
    for(int step:order) {
      if(step>=count) {
        int n=step-count,p=parents[n];global[n]=p==-1?local[n]:global[p].multiply(local[n]);worldRotation[n]=p==-1?rotation[n]:worldRotation[p].multiply(rotation[n]);continue;
      }
      int n=step;var c=constraints[n];if(c==null) continue;Rotation target;
      if(c.type()==ConstraintType.ROTATION) target=rest[n].multiply(rest[c.source()].inverse().multiply(rotation[c.source()]));
      else if(c.type()==ConstraintType.ROLL) {
        Rotation delta=rest[n].inverse().multiply(rotation[c.source()].multiply(rest[c.source()].inverse())).multiply(rest[n]);
        Rotation swing=VrmMath.fromTo(c.axis(),delta.rotate(c.axis()));target=rest[n].multiply(swing.inverse()).multiply(delta);
      } else {
        int p=parents[n];Rotation parent=p==-1?Rotation.IDENTITY:worldRotation[p];
        Vec3 position=p==-1?VrmMath.position(local[n]):global[p].transformPoint(VrmMath.position(local[n]));
        Vec3 to=VrmMath.position(global[c.source()]).subtract(position),from=parent.multiply(rest[n]).rotate(c.axis());
        target=parent.inverse().multiply(VrmMath.fromTo(from,to)).multiply(parent).multiply(rest[n]);
      }
      Rotation result=Rotation.slerp(rest[n],target,c.weight());local[n]=VrmMath.rotate(local[n],rotation[n],result);rotation[n]=result;
    }
    return new Frame(new ScenePose(input.seconds(),Arrays.asList(local),Arrays.asList(global),input.morphWeights(),input.extensionChannels()),Arrays.asList(rotation));
  }
}
