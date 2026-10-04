package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cc.sirrus.ysmlib.scene.mmd.PmdDocument.*;

public final class PmdReader {
  public PmdDocument read(ByteData source,ReadLimits limits) throws AssetFormatException {
    var in=new BinaryInput(source,limits);
    if(!in.fixed(3,StandardCharsets.US_ASCII).equals("Pmd") || in.f32()!=1f) throw in.error("Unsupported PMD signature/version");
    String name=in.fixed(20,BinaryInput.MS932),comment=in.fixed(256,BinaryInput.MS932);
    int n=in.count(38);var vertices=new ArrayList<Vertex>(n);
    for(int i=0;i<n;i++) {
      var p=in.vec3();var normal=in.vec3();var uv=in.floats(2);int b0=in.u16(),b1=in.u16(),weight=in.u8(),edge=in.u8();
      if(weight>100 || edge>1) throw in.error("Invalid PMD vertex weight/edge");vertices.add(new Vertex(p,normal,uv,b0,b1,weight,edge));
    }
    n=in.count(2);if(n%3!=0) throw in.error("Incomplete PMD face");int[] indices=new int[n];for(int i=0;i<n;i++) indices[i]=in.u16();
    n=in.count(70);var materials=new ArrayList<Material>(n);
    for(int i=0;i<n;i++) materials.add(new Material(in.vec3(),in.f32(),in.f32(),in.vec3(),in.vec3(),in.u8(),in.u8(),nonnegative(in.i32(),in),in.fixed(20,BinaryInput.MS932)));
    n=in.count(in.u16(),39);var bones=new ArrayList<Bone>(n);
    for(int i=0;i<n;i++) bones.add(new Bone(in.fixed(20,BinaryInput.MS932),in.u16(),in.u16(),in.u8(),in.u16(),in.vec3()));
    n=in.count(in.u16(),11);var ik=new ArrayList<Ik>(n);
    for(int i=0;i<n;i++) {
      int controller=in.u16(),target=in.u16(),count=in.u8(),iterations=in.u16();float angle=in.f32();in.count(count,2);
      int[] links=new int[count];for(int j=0;j<count;j++) links[j]=in.u16();ik.add(new Ik(controller,target,iterations,angle,new IntData(links)));
    }
    n=in.count(in.u16(),25);var morphs=new ArrayList<Morph>(n);
    for(int i=0;i<n;i++) {
      String mn=in.fixed(20,BinaryInput.MS932);int count=in.count(16),type=in.u8();if(type>4) throw in.error("Invalid PMD morph kind");
      var offsets=new ArrayList<MorphVertex>(count);for(int j=0;j<count;j++) offsets.add(new MorphVertex(nonnegative(in.i32(),in),in.vec3()));morphs.add(new Morph(mn,type,offsets));
    }
    n=in.count(in.u8(),2);int[] morphDisplay=new int[n];for(int i=0;i<n;i++) morphDisplay[i]=in.u16();
    n=in.count(in.u8(),50);var frames=new ArrayList<String>(n);for(int i=0;i<n;i++) frames.add(in.fixed(50,BinaryInput.MS932));
    n=in.count(3);var displays=new ArrayList<BoneDisplay>(n);for(int i=0;i<n;i++) displays.add(new BoneDisplay(in.u16(),in.u8()));
    English english=null;int sections=0;var toon=new ArrayList<String>();var bodies=new ArrayList<PmxDocument.RigidBody>();var joints=new ArrayList<PmxDocument.Joint>();
    if(in.remaining()>0) {
      sections++;int enabled=in.u8();if(enabled>1) throw in.error("Invalid PMD English extension flag");
      if(enabled==1) {
        String en=in.fixed(20,BinaryInput.MS932),ec=in.fixed(256,BinaryInput.MS932);var eb=new ArrayList<String>();var em=new ArrayList<String>();var ef=new ArrayList<String>();
        for(int i=0;i<bones.size();i++) eb.add(in.fixed(20,BinaryInput.MS932));
        for(int i=1;i<morphs.size();i++) em.add(in.fixed(20,BinaryInput.MS932));
        for(int i=0;i<frames.size();i++) ef.add(in.fixed(50,BinaryInput.MS932));english=new English(en,ec,eb,em,ef);
      }
    }
    if(in.remaining()>0) { sections++;for(int i=0;i<10;i++) toon.add(in.fixed(100,BinaryInput.MS932)); }
    if(in.remaining()>0) {
      sections++;n=in.count(83);
      for(int i=0;i<n;i++) {
        var bn=new PmxDocument.Names(in.fixed(20,BinaryInput.MS932),"");int bone=in.u16();if(bone==65535) bone=-1;
        bodies.add(new PmxDocument.RigidBody(bn,bone,in.u8(),in.u16(),in.u8(),in.vec3(),in.vec3(),in.vec3(),in.f32(),in.f32(),in.f32(),in.f32(),in.f32(),in.u8()));
      }
      n=in.count(124);
      for(int i=0;i<n;i++) joints.add(new PmxDocument.Joint(new PmxDocument.Names(in.fixed(20,BinaryInput.MS932),""),0,in.i32(),in.i32(),in.vec3(),in.vec3(),in.vec3(),in.vec3(),in.vec3(),in.vec3(),in.vec3(),in.vec3()));
    }
    if(in.remaining()!=0) throw in.error("Unknown PMD trailing data");
    var result=new PmdDocument(name,comment,vertices,new IntData(indices),materials,bones,ik,morphs,new IntData(morphDisplay),frames,displays,english,toon,bodies,joints,sections,source);
    validate(result,in);return result;
  }
  private static int nonnegative(int value,BinaryInput in) throws AssetFormatException { if(value<0) throw in.error("Negative PMD count/index");return value; }
  private static void ref(int value,int size,boolean nullable,BinaryInput in) throws AssetFormatException { if(value<(nullable?-1:0) || value>=size) throw in.error("PMD reference out of range"); }
  private static void validate(PmdDocument d,BinaryInput in) throws AssetFormatException {
    for(int value:d.indices().copy()) ref(value,d.vertices().size(),false,in);
    for(var v:d.vertices()) { ref(v.bone0()==65535?-1:v.bone0(),d.bones().size(),true,in);ref(v.bone1()==65535?-1:v.bone1(),d.bones().size(),true,in); }
    long total=0;for(var m:d.materials()) { total+=m.indexCount();if(m.indexCount()%3!=0 || m.edgeFlag()>1 || m.toonIndex()!=255 && m.toonIndex()>9) throw in.error("Invalid PMD material"); }
    if(total!=d.indices().size()) throw in.error("PMD material ranges do not cover indices");
    int[] parents=new int[d.bones().size()];
    for(int i=0;i<parents.length;i++) {
      var b=d.bones().get(i);parents[i]=b.parent()==65535?-1:b.parent();ref(parents[i],parents.length,true,in);if(b.type()>9) throw in.error("Unknown PMD bone kind");
      if(b.type()==5) ref(b.ikParent(),parents.length,false,in);
      if(b.type()==8 || b.type()==9) ref(b.tail(),parents.length,false,in);
    }
    byte[] state=new byte[parents.length];for(int i=0;i<parents.length;i++) {
      int n=i;while(n!=-1 && state[n]==0) { state[n]=1;n=parents[n]; }if(n!=-1 && state[n]==1) throw in.error("Cyclic PMD bone hierarchy");
      n=i;while(n!=-1 && state[n]==1) { state[n]=2;n=parents[n]; }
    }
    for(var ik:d.ik()) { ref(ik.controller(),parents.length,false,in);ref(ik.target(),parents.length,false,in);for(int link:ik.links().copy()) ref(link,parents.length,false,in); }
    if(!d.morphs().isEmpty() && d.morphs().get(0).type()!=0) throw in.error("PMD base morph must be first");
    for(int i=0;i<d.morphs().size();i++) {
      var m=d.morphs().get(i);if(i>0 && m.type()==0) throw in.error("Multiple PMD base morphs");
      for(var v:m.vertices()) ref(v.index(),i==0?d.vertices().size():d.morphs().get(0).vertices().size(),false,in);
    }
    for(int morph:d.morphDisplay().copy()) ref(morph,d.morphs().size(),false,in);
    for(var display:d.boneDisplay()) { ref(display.bone(),parents.length,false,in);if(display.frame()>d.frameNames().size()) throw in.error("Invalid PMD display frame"); }
    for(var body:d.rigidBodies()) { ref(body.bone(),parents.length,true,in);if(body.group()>15 || body.shape()>2 || body.mode()>2) throw in.error("Invalid PMD rigid body"); }
    for(var joint:d.joints()) { ref(joint.bodyA(),d.rigidBodies().size(),true,in);ref(joint.bodyB(),d.rigidBodies().size(),true,in); }
  }
}
