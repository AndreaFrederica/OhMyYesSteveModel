package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.io.AssetFormatException;
import java.util.*;
import java.util.function.Function;
import static cc.sirrus.ysmlib.scene.mmd.PmmDocument.*;

/** Structural validation is independent of editor selection state and arbitrary source path spelling. */
final class PmmValidation {
  static void validate(PmmDocument d) throws AssetFormatException {
    var ids=new HashSet<Integer>();
    for(var m:d.models()) {
      if(!ids.add(m.index())) fail("Duplicate model ID");
      for(int b:m.ikBones().copy()) reference(b,m.boneNames().size(),"IK bone",false);
      for(int b:m.outsideBones().copy()) reference(b,m.boneNames().size(),"outside bone",false);
      owners(m.boneKeys(),m.boneNames().size(),BoneKey::key);owners(m.morphKeys(),m.morphNames().size(),MorphKey::key);owners(m.modelKeys(),1,ModelKey::key);
      for(var k:m.modelKeys()) for(var p:k.outsideParents()) parent(d,p);
      for(var p:m.outsideStates()) parent(d,p.parent());
    }
    ids.clear();for(var a:d.accessories()) { if(!ids.add(a.index())) fail("Duplicate accessory ID");owners(a.keys(),1,AccessoryKey::key);parent(d,a.current().parent());for(var k:a.keys()) parent(d,k.state().parent()); }
    owners(d.camera().keys(),1,CameraKey::key);for(var k:d.camera().keys()) parent(d,k.parent());
    owners(d.light().keys(),1,LightKey::key);
    if(d.gravity()!=null) owners(d.gravity().keys(),1,GravityKey::key);
    if(d.shadow()!=null) owners(d.shadow().keys(),1,ShadowKey::key);
  }
  static <T> int[] owners(List<T> records,int initial,Function<T,Key> key) throws AssetFormatException {
    if(records.size()<initial) { fail("Missing initial PMM keys"); }
    var ids=new HashMap<Integer,Integer>();int[] owner=new int[records.size()];Arrays.fill(owner,-1);
    for(int i=0;i<records.size();i++) { var k=key.apply(records.get(i));if(k.index()<0 || ids.put(k.index(),i)!=null) fail("Invalid or duplicate PMM key ID"); }
    for(int root=0;root<initial;root++) {
      int i=root;Key previous=null;
      while(true) {
        if(owner[i]>=0) fail("Cyclic or multiply-owned PMM track");owner[i]=root;var k=key.apply(records.get(i));
        if(previous!=null && (k.previous()!=previous.index() || k.frame()<=previous.frame())) fail("Inconsistent PMM key chain: "+previous+" -> "+k);
        if(k.next()==0) break;
        Integer next=ids.get(k.next());if(next==null || next<initial) fail("Invalid PMM next key reference");previous=k;i=next;
      }
    }
    for(int i=0;i<owner.length;i++) if(owner[i]<0) fail("Unreachable PMM key record "+key.apply(records.get(i)).index());
    return owner;
  }
  private static void parent(PmmDocument d,Parent p) throws AssetFormatException {
    reference(p.model(),d.models().size(),"parent model",true);if(p.model()>=0) reference(p.bone(),d.models().get(p.model()).boneNames().size(),"parent bone",true);
  }
  private static void reference(int value,int count,String label,boolean nullable) throws AssetFormatException { if(value<(nullable?-1:0) || value>=count) fail("Invalid PMM "+label); }
  private static void fail(String message) throws AssetFormatException { throw new AssetFormatException(message); }
}
