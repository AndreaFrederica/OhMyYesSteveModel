package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.nio.charset.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;

/** Bounded PMX 2.0/2.1 reader, including QDEF, all morphs, all joint records and soft bodies. */
public final class PmxReader {
  public PmxDocument read(ByteData source,ReadLimits limits) throws AssetFormatException {
    return new Reader(source,limits).read();
  }
  private static final class Reader {
    final BinaryInput in;final ByteData source;Charset encoding;float version;int uv,vi,ti,mi,bi,oi,ri;
    Reader(ByteData source,ReadLimits limits) throws AssetFormatException { this.source=source;in=new BinaryInput(source,limits); }
    String text() throws AssetFormatException { return in.string(encoding); }
    Names names() throws AssetFormatException { return new Names(text(),text()); }
    int index(int width) throws AssetFormatException { return in.index(width,false); }
    boolean flag() throws AssetFormatException { return range(in.u8(),0,1,"boolean")!=0; }
    int range(int value,int min,int max,String label) throws AssetFormatException {
      if(value<min || value>max) throw in.error("Invalid "+label);return value;
    }
    int nonnegative(int value,String label) throws AssetFormatException { return range(value,0,Integer.MAX_VALUE,label); }
    PmxDocument read() throws AssetFormatException {
      if(!in.fixed(4,StandardCharsets.US_ASCII).equals("PMX ")) throw in.error("Invalid PMX signature");
      version=in.f32();if(version!=2.0f && version!=2.1f) throw in.error("Unsupported PMX version");
      int globalsCount=in.u8();if(globalsCount<8) throw in.error("Incomplete PMX globals");
      var globals=in.bytes(globalsCount);
      encoding=switch(globals.unsigned(0)) { case 0 -> StandardCharsets.UTF_16LE;case 1 -> StandardCharsets.UTF_8;default -> throw in.error("Unknown PMX text encoding"); };
      uv=range(globals.unsigned(1),0,4,"additional UV count");
      for(int i=2;i<8;i++) { int w=globals.unsigned(i);if(w!=1 && w!=2 && w!=4) throw in.error("Invalid PMX index width"); }
      vi=globals.unsigned(2);ti=globals.unsigned(3);mi=globals.unsigned(4);bi=globals.unsigned(5);oi=globals.unsigned(6);ri=globals.unsigned(7);
      var names=names();String comment=text(),englishComment=text();
      int n=in.count(37+bi+uv*16);var vertices=new ArrayList<Vertex>(n);
      for(int i=0;i<n;i++) vertices.add(vertex());
      n=in.count(vi);if(n%3!=0) throw in.error("PMX face index count is not divisible by three");
      int[] indices=new int[n];for(int i=0;i<n;i++) indices[i]=in.index(vi,true);
      n=in.count(4);var textures=new ArrayList<String>(n);for(int i=0;i<n;i++) textures.add(text());
      n=in.count(12);var materials=new ArrayList<Material>(n);for(int i=0;i<n;i++) materials.add(material());
      n=in.count(26);var bones=new ArrayList<Bone>(n);for(int i=0;i<n;i++) bones.add(bone());
      n=in.count(14);var morphs=new ArrayList<Morph>(n);for(int i=0;i<n;i++) morphs.add(morph());
      n=in.count(13);var displays=new ArrayList<DisplayFrame>(n);
      for(int i=0;i<n;i++) {
        var dn=names();boolean special=flag();int size=in.count(2);var elements=new ArrayList<DisplayElement>(size);
        for(int j=0;j<size;j++) { boolean morph=flag();elements.add(new DisplayElement(morph,index(morph?oi:bi))); }
        displays.add(new DisplayFrame(dn,special,elements));
      }
      n=in.count(8+bi+61);var bodies=new ArrayList<RigidBody>(n);for(int i=0;i<n;i++) bodies.add(body());
      n=in.count(8+1+2*ri+96);var joints=new ArrayList<Joint>(n);for(int i=0;i<n;i++) joints.add(joint());
      var soft=new ArrayList<SoftBody>();
      if(version==2.1f) { n=in.count(141+mi);for(int i=0;i<n;i++) soft.add(softBody()); }
      if(in.remaining()!=0) throw in.error("Unknown PMX trailing data");
      var document=new PmxDocument(version,globals,names,comment,englishComment,vertices,new IntData(indices),textures,materials,bones,morphs,displays,bodies,joints,soft,source);
      PmxValidator.validate(document);return document;
    }
    Vertex vertex() throws AssetFormatException {
      var p=in.vec3();var normal=in.vec3();var uvData=in.floats(2);var extra=in.floats(uv*4);
      int deform=range(in.u8(),0,version==2.1f?4:3,"deform type"),count=deform==0?1:(deform==1 || deform==3?2:4);
      int[] bones=new int[count];for(int i=0;i<count;i++) bones[i]=index(bi);
      float[] weights=new float[count];
      if(count==1) weights[0]=1;else if(count==2) { weights[0]=in.f32();weights[1]=1-weights[0]; }
      else for(int i=0;i<count;i++) weights[i]=in.f32();
      Vec3 c=null,r0=null,r1=null;if(deform==3) { c=in.vec3();r0=in.vec3();r1=in.vec3(); }
      return new Vertex(p,normal,uvData,extra,deform,new IntData(bones),new FloatData(weights),c,r0,r1,in.f32());
    }
    Material material() throws AssetFormatException {
      var name=names();var diffuse=in.floats(4);var specular=in.vec3();float power=in.f32();var ambient=in.vec3();int flags=in.u8();
      var edge=in.floats(4);float edgeSize=in.f32();int texture=index(ti),sphere=index(ti),sphereMode=range(in.u8(),0,3,"sphere mode");
      boolean shared=flag();int toon=shared?range(in.u8(),0,9,"shared toon"):index(ti);String memo=text();
      return new Material(name,diffuse,specular,power,ambient,flags,edge,edgeSize,texture,sphere,sphereMode,shared,toon,memo,nonnegative(in.i32(),"material index count"));
    }
    Bone bone() throws AssetFormatException {
      var name=names();var p=in.vec3();int parent=index(bi),layer=in.i32(),flags=in.u16(),tail=-1;Vec3 tailOffset=null;
      if((flags&1)!=0) tail=index(bi);else tailOffset=in.vec3();
      Inherit inherit=(flags&0x300)!=0?new Inherit(index(bi),in.f32()):null;
      Vec3 fixed=(flags&0x400)!=0?in.vec3():null;Axes axes=(flags&0x800)!=0?new Axes(in.vec3(),in.vec3()):null;
      Integer external=(flags&0x2000)!=0?in.i32():null;Ik ik=null;
      if((flags&0x20)!=0) {
        int target=index(bi),iterations=nonnegative(in.i32(),"IK iterations");float angle=in.f32();int count=in.count(bi+1);var links=new ArrayList<IkLink>(count);
        for(int i=0;i<count;i++) { int bone=index(bi);boolean limited=flag();links.add(new IkLink(bone,limited?in.vec3():null,limited?in.vec3():null)); }
        ik=new Ik(target,iterations,angle,links);
      }
      return new Bone(name,p,parent,layer,flags,tail,tailOffset,inherit,fixed,axes,external,ik);
    }
    Morph morph() throws AssetFormatException {
      var name=names();int panel=range(in.u8(),0,4,"morph panel"),type=range(in.u8(),0,version==2.1f?10:8,"morph type");
      int count=in.count(1);var offsets=new ArrayList<MorphOffset>(count);
      for(int i=0;i<count;i++) offsets.add(switch(type) {
        case 0,9 -> new GroupOffset(index(oi),in.f32());
        case 1 -> new VertexOffset(in.index(vi,true),in.vec3());
        case 2 -> new BoneOffset(index(bi),in.vec3(),in.floats(4));
        case 3,4,5,6,7 -> new UvOffset(in.index(vi,true),in.floats(4));
        case 8 -> new MaterialOffset(index(mi),range(in.u8(),0,1,"material morph operation"),in.floats(4),in.vec3(),in.f32(),in.vec3(),in.floats(4),in.f32(),in.floats(4),in.floats(4),in.floats(4));
        case 10 -> new ImpulseOffset(index(ri),flag(),in.vec3(),in.vec3());
        default -> throw new AssertionError();
      });
      return new Morph(name,panel,type,offsets);
    }
    RigidBody body() throws AssetFormatException {
      return new RigidBody(names(),index(bi),range(in.u8(),0,15,"collision group"),in.u16(),range(in.u8(),0,2,"rigid shape"),in.vec3(),in.vec3(),in.vec3(),
          in.f32(),in.f32(),in.f32(),in.f32(),in.f32(),range(in.u8(),0,2,"rigid mode"));
    }
    Joint joint() throws AssetFormatException {
      return new Joint(names(),range(in.u8(),0,version==2.1f?5:0,"joint type"),index(ri),index(ri),in.vec3(),in.vec3(),in.vec3(),in.vec3(),in.vec3(),in.vec3(),in.vec3(),in.vec3());
    }
    SoftBody softBody() throws AssetFormatException {
      var name=names();int shape=range(in.u8(),0,1,"soft shape"),material=index(mi),group=range(in.u8(),0,15,"soft group"),mask=in.u16(),flags=in.u8();
      int bending=nonnegative(in.i32(),"bending distance"),clusters=nonnegative(in.i32(),"clusters");float mass=in.f32(),margin=in.f32();int aero=range(in.i32(),0,4,"aero model");
      var config=in.floats(12);var cluster=in.floats(6);int[] iterations=new int[4];for(int i=0;i<4;i++) iterations[i]=nonnegative(in.i32(),"soft solver iterations");
      var stiffness=in.floats(3);int n=in.count(ri+vi+1);var anchors=new ArrayList<Anchor>(n);
      for(int i=0;i<n;i++) anchors.add(new Anchor(index(ri),in.index(vi,true),flag()));
      n=in.count(vi);int[] pins=new int[n];for(int i=0;i<n;i++) pins[i]=in.index(vi,true);
      return new SoftBody(name,shape,material,group,mask,flags,bending,clusters,mass,margin,aero,config,cluster,new IntData(iterations),stiffness,anchors,new IntData(pins));
    }
  }
}
