package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

final class VrmTopology {
  private VrmTopology() {}
  static int[] parents(SceneAsset scene) {
    int[] p=new int[scene.nodes().size()];Arrays.fill(p,-1);for(int n=0;n<p.length;n++) for(int c:scene.nodes().get(n).children().copy()) p[c]=n;return p;
  }
  static void validateSprings(VrmDocument document) {
    var scene=document.scene();int[] parents=parents(scene),owner=new int[parents.length];Arrays.fill(owner,-1);int id=0;
    for(var spring:document.springs()) {
      var joints=spring.joints();int previous=-1;
      for(var joint:joints) {
        int n=joint.node();if(previous!=-1) {
          int c=parents[n];while(c!=-1 && c!=previous) { claim(owner,c,id);c=parents[c]; }
          if(c==-1) throw new IllegalArgumentException("Spring head must be an ancestor of its tail");
        }claim(owner,n,id);previous=n;
      }id++;
    }
    for(var spring:document.legacySprings()) {
      var queue=new ArrayDeque<Integer>();for(int root:spring.roots().copy()) queue.add(root);
      while(!queue.isEmpty()) { int n=queue.remove();claim(owner,n,id);for(int c:scene.nodes().get(n).children().copy()) queue.add(c); }
      id++;
    }
  }
  private static void claim(int[] owner,int node,int id) {
    if(owner[node]!=-1) throw new IllegalArgumentException("Duplicated or overlapping spring joint: "+node);owner[node]=id;
  }
}
