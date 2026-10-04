package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmJson.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmDocument.*;

final class VrmLegacy {
  private VrmLegacy() {}
  static List<Expression> expressions(JsonObject root,SceneAsset scene) {
    var out=new ArrayList<Expression>();var keys=new HashSet<String>();
    for(var value:array(object(root,"blendShapeMaster"),"blendShapeGroups")) {
      var e=value.getAsJsonObject();String name=string(e,"name",""),preset=string(e,"presetName","unknown"),key=preset.equals("unknown")?name:preset;
      if(key.isEmpty() || !keys.add(key)) throw new IllegalArgumentException("Invalid or duplicate legacy expression: "+key);
      var morphs=new ArrayList<MorphBind>();var materials=new ArrayList<MaterialBind>();
      for(var v:array(e,"binds")) {
        var b=v.getAsJsonObject();int mesh=index(integer(b,"mesh",-1),scene.meshes().size(),"legacy expression mesh"),m=integer(b,"index",-1);
        float w=range(number(b,"weight",100),0,100,"legacy morph weight")/100;
        for(var primitive:scene.meshes().get(mesh).primitives()) index(m,primitive.morphs().size(),"legacy morph");
        for(int n=0;n<scene.nodes().size();n++) if(scene.nodes().get(n).mesh()==mesh) morphs.add(new MorphBind(n,m,w));
      }
      for(var v:array(e,"materialValues")) {
        var b=v.getAsJsonObject();String material=string(b,"materialName",""),property=string(b,"propertyName","");
        FloatData target=vector(b.get("targetValue"));boolean found=false;
        for(int m=0;m<scene.materials().size();m++) if(scene.materials().get(m).name().equals(material)) { found=true;materials.add(new MaterialBind(m,property,target)); }
        if(!found) throw new IllegalArgumentException("Missing legacy expression material: "+material);
      }
      out.add(new Expression(key,name,preset,bool(e,"isBinary",false),VrmDocument.Override.NONE,VrmDocument.Override.NONE,VrmDocument.Override.NONE,morphs,materials,List.of()));
    }return List.copyOf(out);
  }
  static List<LegacyMaterial> materials(JsonObject root,SceneAsset scene) {
    var out=new ArrayList<LegacyMaterial>();for(var v:array(root,"materialProperties")) {
      var m=v.getAsJsonObject();var values=new LinkedHashMap<String,FloatData>();var textures=new LinkedHashMap<String,Integer>();var keywords=new LinkedHashMap<String,Boolean>();var tags=new LinkedHashMap<String,String>();
      for(var e:object(m,"floatProperties").entrySet()) values.put(e.getKey(),new FloatData(number(e.getValue())));
      for(var e:object(m,"vectorProperties").entrySet()) {
        if(values.putIfAbsent(e.getKey(),vector(e.getValue()))!=null) throw new IllegalArgumentException("Duplicated legacy shader property");
      }
      for(var e:object(m,"textureProperties").entrySet()) textures.put(e.getKey(),optionalIndex(integer(e.getValue()),scene.textures().size(),"legacy texture"));
      var kw=object(m,"keywordMap");for(String k:kw.keySet()) keywords.put(k,bool(kw,k,false));
      var tm=object(m,"tagMap");for(String k:tm.keySet()) tags.put(k,string(tm,k,""));
      out.add(new LegacyMaterial(string(m,"name",""),string(m,"shader","VRM_USE_GLTFSHADER"),integer(m,"renderQueue",-1),values,textures,keywords,tags));
    }
    if(!out.isEmpty() && out.size()!=scene.materials().size()) throw new IllegalArgumentException("VRM legacy material count mismatch");return List.copyOf(out);
  }
  static void springs(JsonObject root,SceneAsset scene,List<Collider> colliders,List<ColliderGroup> groups,List<LegacySpring> springs) {
    var secondary=object(root,"secondaryAnimation");for(var value:array(secondary,"colliderGroups")) {
      var g=value.getAsJsonObject();int node=index(integer(g,"node",-1),scene.nodes().size(),"legacy collider node");var ids=new ArrayList<Integer>();
      for(var v:array(g,"colliders")) {
        var c=v.getAsJsonObject();ids.add(colliders.size());colliders.add(new Collider(node,legacyVec3(c,"offset",Vec3.ZERO),null,range(number(c,"radius",0),0,Float.MAX_VALUE,"radius")));
      }groups.add(new ColliderGroup("",new IntData(ids.stream().mapToInt(Integer::intValue).toArray())));
    }
    for(var value:array(secondary,"boneGroups")) {
      var s=value.getAsJsonObject();var params=new Joint(-1,range(number(s,"hitRadius",0),0,Float.MAX_VALUE,"hitRadius"),range(number(s,"stiffiness",1),0,Float.MAX_VALUE,"stiffiness"),
          range(number(s,"gravityPower",0),0,Float.MAX_VALUE,"gravityPower"),legacyVec3(s,"gravityDir",new Vec3(0,-1,0)),range(number(s,"dragForce",.4f),0,1,"dragForce"));
      springs.add(new LegacySpring(string(s,"comment",""),optionalIndex(integer(s,"center",-1),scene.nodes().size(),"legacy center"),indices(array(s,"bones"),scene.nodes().size(),"legacy spring root"),params,indices(array(s,"colliderGroups"),groups.size(),"legacy collider group")));
    }
  }
  static FirstPerson firstPerson(JsonObject root,SceneAsset scene,Map<String,Integer> bones) {
    var fp=object(root,"firstPerson");int bone=integer(fp,"firstPersonBone",-1);if(bone==-1) bone=bones.get("head");index(bone,scene.nodes().size(),"first-person bone");
    var out=new ArrayList<MeshAnnotation>();var seen=new HashSet<Integer>();for(var value:array(fp,"meshAnnotations")) {
      var a=value.getAsJsonObject();int mesh=index(integer(a,"mesh",-1),scene.meshes().size(),"first-person mesh");if(!seen.add(mesh)) throw new IllegalArgumentException("Duplicate first-person mesh");
      String type=switch(string(a,"firstPersonFlag","Auto")) { case "Auto"->"auto";case "Both"->"both";case "ThirdPersonOnly"->"thirdPersonOnly";case "FirstPersonOnly"->"firstPersonOnly";default->throw new IllegalArgumentException("Invalid legacy first-person flag"); };
      for(int n=0;n<scene.nodes().size();n++) if(scene.nodes().get(n).mesh()==mesh) out.add(new MeshAnnotation(n,type));
    }return new FirstPerson(bone,legacyVec3(fp,"firstPersonBoneOffset",Vec3.ZERO),out);
  }
  static LookAt lookAt(JsonObject root) {
    if(!root.has("firstPerson")) return null;var fp=object(root,"firstPerson");String type=switch(string(fp,"lookAtTypeName","Bone")) {
      case "Bone"->"bone";case "BlendShape"->"expression";default->throw new IllegalArgumentException("Invalid legacy gaze type");
    };float output=type.equals("expression")?1:10;
    return new LookAt(type,legacyVec3(fp,"firstPersonBoneOffset",new Vec3(0,.06f,0)),rangeMap(fp,"lookAtHorizontalInner",output),rangeMap(fp,"lookAtHorizontalOuter",output),rangeMap(fp,"lookAtVerticalDown",output),rangeMap(fp,"lookAtVerticalUp",output));
  }
  static RangeMap rangeMap(JsonObject fp,String name,float output) {
    var r=object(fp,name);var curve=r.has("curve")?vector(r.get("curve")):new FloatData(0,0,0,1,1,1,1,0);
    if(curve.size()%4!=0 || curve.size()==0) throw new IllegalArgumentException("Invalid legacy lookAt curve");
    for(int i=4;i<curve.size();i+=4) if(curve.get(i)<=curve.get(i-4)) throw new IllegalArgumentException("Unordered legacy lookAt curve");
    return new RangeMap(range(number(r,"xRange",90),0,Float.MAX_VALUE,"xRange"),number(r,"yRange",output),curve);
  }
}
