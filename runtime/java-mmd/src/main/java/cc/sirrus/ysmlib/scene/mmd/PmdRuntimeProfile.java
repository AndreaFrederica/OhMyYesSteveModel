package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;

/** Internal runtime projection; the public PMD document and source metadata remain intact. */
final class PmdRuntimeProfile {
  record Constraint(int controller,Ik ik) {}
  final PmxDocument asset;
  final List<Constraint> constraints;
  PmdRuntimeProfile(PmdDocument source) {
    var bones=new ArrayList<Bone>();
    for(int i=0;i<source.bones().size();i++) {
      var b=source.bones().get(i);int flags=0x1a,tail=b.tail()>0 && b.tail()!=65535?b.tail():-1;Inherit inherit=null;Vec3 fixed=null;
      if(b.type()==1 || b.type()==2) flags|=4;
      if(b.type()==5) { inherit=new Inherit(b.ikParent(),1);flags|=0x100; }
      if(b.type()==9) { inherit=new Inherit(b.tail(),b.ikParent()*.01f);flags|=0x100; }
      if(b.type()==8) { fixed=source.bones().get(b.tail()).position().subtract(b.position());flags|=0x400; }
      if(b.type()==6 || b.type()==7 || b.type()==9) flags&=~8;
      String english=source.english()==null?"":source.english().bones().get(i);
      bones.add(new Bone(new Names(b.name(),english),b.position(),b.parent()==65535?-1:b.parent(),0,flags,tail,Vec3.ZERO,inherit,fixed,null,null,null));
    }
    var tasks=new ArrayList<Constraint>();
    for(var ik:source.ik()) {
      var links=new ArrayList<IkLink>();
      for(int index:ik.links().copy()) {
        boolean knee=source.bones().get(index).name().endsWith("ひざ");
        links.add(new IkLink(index,knee?new Vec3(-(float)Math.PI,0,0):null,knee?new Vec3(-(float)Math.toRadians(.5),0,0):null));
      }
      tasks.add(new Constraint(ik.controller(),new Ik(ik.target(),ik.iterations(),ik.angleLimit()*4,links)));
    }
    constraints=List.copyOf(tasks);
    var base=new HashMap<Integer,Vec3>();if(!source.morphs().isEmpty()) for(var v:source.morphs().get(0).vertices()) base.put(v.index(),v.position());
    var vertices=new ArrayList<Vertex>();
    for(int i=0;i<source.vertices().size();i++) {
      var v=source.vertices().get(i);float weight=v.weightPercent()*.01f;
      vertices.add(new Vertex(base.getOrDefault(i,v.position()),v.normal(),v.uv(),FloatData.EMPTY,1,
          new IntData(v.bone0()==65535?-1:v.bone0(),v.bone1()==65535?-1:v.bone1()),new FloatData(weight,1-weight),null,null,null,v.edgeFlag()==0?1:0));
    }
    var morphs=new ArrayList<Morph>();
    for(int i=0;i<source.morphs().size();i++) {
      var m=source.morphs().get(i);var offsets=new ArrayList<MorphOffset>();
      if(i>0) for(var v:m.vertices()) offsets.add(new VertexOffset(source.morphs().get(0).vertices().get(v.index()).index(),v.position()));
      String english=source.english()==null || i==0?"":source.english().morphs().get(i-1);
      morphs.add(new Morph(new Names(m.name(),english),m.type(),1,offsets));
    }
    var textures=new ArrayList<String>();var materials=new ArrayList<Material>();
    for(var m:source.materials()) {
      int diffuse=-1,sphere=-1,sphereMode=0;
      for(String name:m.textureNames().split("\\*",-1)) if(!name.isEmpty()) {
        String lower=name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".sph") || lower.endsWith(".spa")) {
          if(sphere!=-1) throw new IllegalArgumentException("Multiple PMD sphere textures in one material");
          sphere=texture(textures,name);sphereMode=lower.endsWith(".spa")?2:1;
        } else { if(diffuse!=-1) throw new IllegalArgumentException("Multiple PMD diffuse textures in one material");diffuse=texture(textures,name); }
      }
      boolean shared=false;int toon=-1;
      if(m.toonIndex()!=255) {
        String name=source.toonTextures().isEmpty()?String.format(Locale.ROOT,"toon%02d.bmp",m.toonIndex()+1):source.toonTextures().get(m.toonIndex());
        // A bare default name can be overridden beside the model; only package resolution decides fallback.
        if(!name.isEmpty()) toon=texture(textures,name);
      }
      int flags=(m.alpha()<1?1:0)|(m.edgeFlag()!=0?16:0)|2;
      if(Math.abs(m.alpha()-.98f)>1e-7) flags|=12;
      materials.add(new Material(new Names("material-"+materials.size(),""),new FloatData(m.diffuse().x(),m.diffuse().y(),m.diffuse().z(),m.alpha()),
          m.specular(),m.shininess(),m.ambient(),flags,new FloatData(0,0,0,1),m.edgeFlag()==0?0:1,diffuse,sphere,sphereMode,shared,toon,"",m.indexCount()));
    }
    var bodies=new ArrayList<RigidBody>();
    for(var b:source.rigidBodies()) {
      int driver=b.bone()<0 && !bones.isEmpty()?0:b.bone();Vec3 position=b.position();if(driver>=0) position=position.add(bones.get(driver).position());
      bodies.add(new RigidBody(b.names(),b.bone(),b.group(),b.collisionMask(),b.shape(),b.size(),position,b.euler(),b.mass(),b.linearDamping(),b.angularDamping(),b.restitution(),b.friction(),b.mode()));
    }
    asset=new PmxDocument(2,new ByteData(new byte[]{1,0,4,4,4,4,4,4}),new Names(source.name(),source.english()==null?"":source.english().model()),source.comment(),
        source.english()==null?"":source.english().comment(),vertices,source.indices(),textures,materials,bones,morphs,List.of(),bodies,source.joints(),List.of(),source.source());
  }
  private static int texture(List<String> textures,String path) { int index=textures.indexOf(path);if(index<0) { index=textures.size();textures.add(path); }return index; }
}
