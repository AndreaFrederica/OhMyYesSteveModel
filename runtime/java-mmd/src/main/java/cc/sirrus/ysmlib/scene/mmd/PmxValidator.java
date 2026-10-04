package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.io.AssetFormatException;
import java.util.*;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;

/** Cross-section references are validated after reading, so forward references remain legal. */
final class PmxValidator {
  private PmxValidator() {}
  static void validate(PmxDocument d) throws AssetFormatException {
    for(int i=0;i<d.indices().size();i++) index(d.indices().get(i),d.vertices().size(),false,"face vertex");
    for(var vertex:d.vertices()) for(int i=0;i<vertex.bones().size();i++) {
      index(vertex.bones().get(i),d.bones().size(),true,"vertex bone");
      if(vertex.weights().get(i)<0) fail("Negative skin weight");
    }
    long total=0;
    for(var material:d.materials()) {
      total+=material.indexCount();if(material.indexCount()%3!=0) fail("Incomplete material triangle");
      index(material.texture(),d.textures().size(),true,"material texture");index(material.sphereTexture(),d.textures().size(),true,"sphere texture");
      if(!material.sharedToon()) index(material.toonTexture(),d.textures().size(),true,"toon texture");
    }
    if(total!=d.indices().size()) fail("Material ranges do not cover the face indices");
    int[] parents=new int[d.bones().size()];
    for(int i=0;i<d.bones().size();i++) {
      var bone=d.bones().get(i);parents[i]=bone.parent();index(bone.parent(),parents.length,true,"parent bone");
      index(bone.tailBone(),parents.length,true,"tail bone");
      if(bone.inherit()!=null) index(bone.inherit().parent(),parents.length,true,"inherit bone");
      if(bone.ik()!=null) {
        index(bone.ik().target(),parents.length,false,"IK target");
        for(var link:bone.ik().links()) index(link.bone(),parents.length,false,"IK link");
      }
    }
    // Iterative walk: hostile deep hierarchies cannot overflow the Java stack.
    byte[] state=new byte[parents.length];
    for(int start=0;start<parents.length;start++) {
      int node=start;
      while(node!=-1 && state[node]==0) { state[node]=1;node=parents[node]; }
      if(node!=-1 && state[node]==1) fail("Cyclic bone parent hierarchy");
      node=start;while(node!=-1 && state[node]==1) { state[node]=2;node=parents[node]; }
    }
    var dependencies=new ArrayList<List<Integer>>();
    for(var morph:d.morphs()) {
      var refs=new ArrayList<Integer>();dependencies.add(refs);
      for(var offset:morph.offsets()) {
        if(offset instanceof GroupOffset o) { index(o.morph(),d.morphs().size(),false,"group/flip morph");refs.add(o.morph()); }
        else if(offset instanceof VertexOffset o) index(o.vertex(),d.vertices().size(),false,"vertex morph");
        else if(offset instanceof BoneOffset o) index(o.bone(),d.bones().size(),false,"bone morph");
        else if(offset instanceof UvOffset o) index(o.vertex(),d.vertices().size(),false,"UV morph");
        else if(offset instanceof MaterialOffset o) index(o.material(),d.materials().size(),true,"material morph");
        else if(offset instanceof ImpulseOffset o) index(o.rigidBody(),d.rigidBodies().size(),false,"impulse morph");
      }
    }
    var indegree=new int[dependencies.size()];for(var refs:dependencies) for(int target:refs) indegree[target]++;
    var queue=new ArrayDeque<Integer>();for(int i=0;i<indegree.length;i++) if(indegree[i]==0) queue.add(i);
    int seen=0;while(!queue.isEmpty()) { int i=queue.remove();seen++;for(int child:dependencies.get(i)) if(--indegree[child]==0) queue.add(child); }
    if(seen!=dependencies.size()) fail("Cyclic group/flip morph dependencies");
    for(var display:d.displayFrames()) for(var element:display.elements()) index(element.index(),element.morph()?d.morphs().size():d.bones().size(),false,"display element");
    for(var body:d.rigidBodies()) index(body.bone(),d.bones().size(),true,"rigid body bone");
    for(var joint:d.joints()) { index(joint.bodyA(),d.rigidBodies().size(),true,"joint body A");index(joint.bodyB(),d.rigidBodies().size(),true,"joint body B"); }
    for(var soft:d.softBodies()) {
      index(soft.material(),d.materials().size(),false,"soft material");
      for(var anchor:soft.anchors()) { index(anchor.body(),d.rigidBodies().size(),false,"soft anchor body");index(anchor.vertex(),d.vertices().size(),false,"soft anchor vertex"); }
      for(int i=0;i<soft.pins().size();i++) index(soft.pins().get(i),d.vertices().size(),false,"soft pin");
    }
  }
  private static void index(int value,int count,boolean nullable,String label) throws AssetFormatException {
    if(value<(nullable?-1:0) || value>=count) fail("Invalid "+label+" reference: "+value);
  }
  private static void fail(String message) throws AssetFormatException { throw new AssetFormatException(message); }
}
