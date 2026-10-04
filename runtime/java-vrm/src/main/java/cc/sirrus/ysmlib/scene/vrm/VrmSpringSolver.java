package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.SceneEvaluator;
import java.util.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmDocument.*;

/** VRM Verlet solver. Its entire evolving state is tail history and local rotations, so snapshots are exact. */
public final class VrmSpringSolver implements VrmSpringSimulation {
  private record Segment(int node,int tail,int center,Joint settings,IntData colliders,Vec3 restTail,Rotation restRotation,int[] tailPath) {}
  private record State(Vec3 previous,Vec3 current,Rotation rotation) {}
  private record Saved(Object owner,List<State> states) implements Snapshot { private Saved { states=List.copyOf(states); } }
  private final Object identity=new Object();
  private final VrmDocument document;
  private final int[] parents,order,segmentByNode;
  private final List<Segment> segments;
  private State[] states;
  private cc.sirrus.ysmlib.scene.physics.PhysicsEnvironment environment;
  private boolean resetHost;
  public void environment(cc.sirrus.ysmlib.scene.physics.PhysicsEnvironment input){environment=input;resetHost|=input!=null&&input.reset();}
  public VrmSpringSolver(VrmDocument document) {
    this.document=Objects.requireNonNull(document);VrmTopology.validateSprings(document);parents=VrmTopology.parents(document.scene());
    var rest=new SceneEvaluator(document.scene()).evaluate(null,0);var list=new ArrayList<Segment>();
    for(var spring:document.springs()) for(int j=0;j+1<spring.joints().size();j++) {
      Joint joint=spring.joints().get(j);list.add(segment(joint.node(),spring.joints().get(j+1).node(),spring.center(),joint,spring.colliderGroups(),rest));
    }
    for(var spring:document.legacySprings()) {
      var queue=new ArrayDeque<Integer>();for(int n:spring.roots().copy()) queue.add(n);
      while(!queue.isEmpty()) {
        int n=queue.remove();var children=document.scene().nodes().get(n).children();int tail=children.size()==0?-1:children.get(0);
        list.add(segment(n,tail,spring.center(),spring.parameters(),spring.colliderGroups(),rest));for(int child:children.copy()) queue.add(child);
      }
    }
    segments=List.copyOf(list);segmentByNode=new int[parents.length];Arrays.fill(segmentByNode,-1);
    for(int i=0;i<segments.size();i++) segmentByNode[segments.get(i).node()]=i;
    order=dependencyOrder();states=new State[segments.size()];
    reset(rest,document.scene().nodes().stream().map(VrmMath::restRotation).toList());
  }
  private Segment segment(int node,int tail,int center,Joint params,IntData groups,ScenePose rest) {
    var indices=new LinkedHashSet<Integer>();for(int g:groups.copy()) for(int c:document.colliderGroups().get(g).colliders().copy()) indices.add(c);
    Vec3 tip;if(tail!=-1) tip=rest.globalMatrices().get(node).inverse().transformPoint(VrmMath.position(rest.globalMatrices().get(tail)));
    else { var position=VrmMath.position(rest.localMatrices().get(node));tip=position.dot(position)<1e-20?Vec3.ZERO:position.normalized().multiply(.07f); }
    var path=new ArrayList<Integer>();for(int n=tail;n!=-1 && n!=node;n=parents[n]) path.add(n);Collections.reverse(path);
    return new Segment(node,tail,center,params,new IntData(indices.stream().mapToInt(Integer::intValue).toArray()),tip,VrmMath.restRotation(document.scene().nodes().get(node)),path.stream().mapToInt(Integer::intValue).toArray());
  }
  private int[] dependencyOrder() {
    int count=parents.length;var edges=new ArrayList<List<Integer>>(count*2);int[] degree=new int[count*2];for(int i=0;i<count*2;i++) edges.add(new ArrayList<>());
    for(int n=0;n<count;n++) { edge(edges,degree,n,n+count);if(parents[n]!=-1) edge(edges,degree,parents[n]+count,n+count); }
    for(var s:segments) {
      if(parents[s.node()]!=-1) edge(edges,degree,parents[s.node()]+count,s.node());
      if(s.center()!=-1) edge(edges,degree,s.center()+count,s.node());
      for(int c:s.colliders().copy()) edge(edges,degree,document.colliders().get(c).node()+count,s.node());
    }
    var queue=new ArrayDeque<Integer>();for(int i=0;i<count*2;i++) if(degree[i]==0) queue.add(i);int[] order=new int[count*2];int at=0;
    while(!queue.isEmpty()) { int n=queue.remove();order[at++]=n;for(int target:edges.get(n)) if(--degree[target]==0) queue.add(target); }
    if(at!=order.length) throw new IllegalArgumentException("Cyclic SpringBone dependency through center, collider or parent");return order;
  }
  private static void edge(List<List<Integer>> e,int[] degree,int from,int to) { e.get(from).add(to);degree[to]++; }
  public NodeConstraintEvaluation.Frame reset(ScenePose pose,List<Rotation> rotations) {
    validate(pose,rotations);State[] next=new State[states.length];
    for(int i=0;i<next.length;i++) {
      var s=segments.get(i);Matrix4 center=s.center()==-1?Matrix4.IDENTITY:pose.globalMatrices().get(s.center());
      Vec3 tail=pose.globalMatrices().get(s.node()).transformPoint(s.restTail());tail=center.inverse().transformPoint(tail);
      next[i]=new State(tail,tail,rotations.get(s.node()));
    }states=next;return sample(pose,rotations);
  }
  public NodeConstraintEvaluation.Frame step(ScenePose pose,List<Rotation> rotations,double seconds) {
    if(!Double.isFinite(seconds) || seconds<=0 || seconds>1) throw new IllegalArgumentException("Spring step must be in (0,1] seconds");
    if(resetHost){reset(pose,rotations);resetHost=false;}
    return evaluate(pose,rotations,seconds);
  }
  public NodeConstraintEvaluation.Frame sample(ScenePose pose,List<Rotation> rotations) { return evaluate(pose,rotations,0); }
  private void validate(ScenePose pose,List<Rotation> rotations) {
    if(pose.localMatrices().size()!=parents.length || pose.globalMatrices().size()!=parents.length || rotations.size()!=parents.length) throw new IllegalArgumentException("Spring pose size mismatch");
  }
  private NodeConstraintEvaluation.Frame evaluate(ScenePose pose,List<Rotation> rotations,double dt) {
    validate(pose,rotations);int count=parents.length;Matrix4[] local=pose.localMatrices().toArray(Matrix4[]::new),global=new Matrix4[count];
    Rotation[] resultRotations=rotations.toArray(Rotation[]::new);State[] next=states.clone();
    for(int entry:order) {
      if(entry>=count) { int n=entry-count,p=parents[n];global[n]=p==-1?local[n]:global[p].multiply(local[n]);continue; }
      int n=entry,index=segmentByNode[n];if(index==-1) continue;var s=segments.get(index);var previous=states[index];
      Rotation result=previous.rotation();
      if(dt!=0 && s.restTail().dot(s.restTail())>1e-20) {
        Matrix4 parent=parents[n]==-1?Matrix4.IDENTITY:global[parents[n]];
        Matrix4 initial=VrmMath.rotate(local[n],rotations.get(n),s.restRotation());
        Matrix4 baseline=parent.multiply(initial),current=parent.multiply(VrmMath.rotate(local[n],rotations.get(n),previous.rotation()));
        Matrix4 center=s.center()==-1?Matrix4.IDENTITY:global[s.center()];Vec3 head=VrmMath.position(current);
        Vec3 relativeTip=s.restTail();if(s.tail()!=-1) {
          Matrix4 relative=Matrix4.IDENTITY;for(int t:s.tailPath()) relative=relative.multiply(local[t]);relativeTip=VrmMath.position(relative);
        }
        float length=(float)Math.sqrt(current.transformDirection(relativeTip).dot(current.transformDirection(relativeTip)));
        Vec3 axis=baseline.transformDirection(s.restTail()).normalized();var p=s.settings();
        Vec3 inertial=previous.current().add(previous.current().subtract(previous.previous()).multiply(1-p.dragForce()));
        Vec3 gravity=p.gravityDir();
        if(environment!=null&&environment.sourceGravity().dot(environment.sourceGravity())>1e-20)
          gravity=gravity.add(environment.sourceGravity().normalized().add(new Vec3(0,1,0)).multiply(-gravity.y()));
        Vec3 tail=center.transformPoint(inertial).add(axis.multiply((float)(dt*p.stiffness()))).add(gravity.multiply((float)(dt*p.gravityPower())));
        if(environment!=null) {
          var position=center.transformPoint(previous.current());
          var velocity=center.transformDirection(previous.current().subtract(previous.previous())).multiply((float)(1/dt));
          tail=tail.add(environment.accelerationAt(position,velocity,dt).multiply((float)(dt*dt)));
        }
        tail=lengthConstraint(head,tail,length,axis);
        for(int c:s.colliders().copy()) {
          var collider=document.colliders().get(c);Matrix4 world=global[collider.node()];
          // VRM 0 collider offsets were serialized in Unity coordinates. Source values remain unchanged in VrmDocument.
          Vec3 offset=collider.offset();if(document.version()==Version.VRM_0) offset=new Vec3(offset.x(),offset.y(),-offset.z());
          Vec3 point=world.transformPoint(offset);
          if(collider.capsule()) {
            Vec3 segment=world.transformPoint(collider.tail()).subtract(point);double norm=segment.dot(segment);
            if(norm>1e-20) point=point.add(segment.multiply((float)Math.max(0,Math.min(1,tail.subtract(point).dot(segment)/norm))));
          }
          Vec3 delta=tail.subtract(point);double distance=Math.sqrt(delta.dot(delta)),radius=collider.radius()+p.hitRadius();
          if(distance<radius) {
            Vec3 direction=distance<1e-12?collisionFallback(head,point,axis):delta.multiply((float)(1/distance));
            tail=lengthConstraint(head,point.add(direction.multiply((float)radius)),length,axis);
          }
        }
        if(environment!=null)for(int contact=0;contact<4;contact++)
          tail=lengthConstraint(head,environment.collideTip(tail,p.hitRadius()),length,axis);
        result=s.restRotation().multiply(VrmMath.fromTo(s.restTail(),baseline.inverse().transformPoint(tail)));
        next[index]=new State(previous.current(),center.inverse().transformPoint(tail),result);
      }
      local[n]=VrmMath.rotate(local[n],rotations.get(n),result);resultRotations[n]=result;
    }
    if(dt!=0) states=next;
    return new NodeConstraintEvaluation.Frame(new ScenePose(pose.seconds(),Arrays.asList(local),Arrays.asList(global),pose.morphWeights(),pose.extensionChannels()),Arrays.asList(resultRotations));
  }
  private static Vec3 lengthConstraint(Vec3 head,Vec3 tail,float length,Vec3 fallback) {
    Vec3 delta=tail.subtract(head);return head.add((delta.dot(delta)<1e-24?fallback:delta.normalized()).multiply(length));
  }
  private static Vec3 collisionFallback(Vec3 head,Vec3 point,Vec3 axis) {
    Vec3 away=head.subtract(point);if(away.dot(away)>1e-20) return away.normalized();
    // Coincident center has no geometric normal; choose a stable perpendicular to avoid NaNs.
    return axis.cross(Math.abs(axis.x())<.8?new Vec3(1,0,0):new Vec3(0,1,0)).normalized();
  }
  public Snapshot snapshot() { return new Saved(identity,Arrays.asList(states.clone())); }
  public void restore(Snapshot snapshot) {
    if(!(snapshot instanceof Saved saved) || saved.owner()!=identity) throw new IllegalArgumentException("Snapshot belongs to another SpringBone instance");states=saved.states().toArray(State[]::new);
  }
}
