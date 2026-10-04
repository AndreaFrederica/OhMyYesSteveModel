package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.gltf.GltfReader;
import cc.sirrus.ysmlib.scene.io.*;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmJson.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmDocument.*;

/** The two profiles share storage, not version-specific defaults or algorithms. */
public final class VrmReader {
  public VrmDocument read(ByteData source,AssetResolver resolver,ReadLimits limits) throws IOException {
    return profile(new GltfReader().read(source,resolver,limits));
  }
  public VrmDocument profile(GltfDocument gltf) throws AssetFormatException {
    try { return readProfile(gltf); }
    catch(IllegalArgumentException|IllegalStateException|ClassCastException|ArithmeticException e) {
      throw new AssetFormatException("Invalid VRM profile: "+e.getMessage(),e);
    }
  }
  private VrmDocument readProfile(GltfDocument gltf) {
    var scene=gltf.scene();var ext=extensions(scene.metadata());boolean legacy=ext.has("VRM");
    if(legacy==ext.has("VRMC_vrm")) throw new IllegalArgumentException("Expected exactly one VRM profile");
    var root=object(ext,legacy?"VRM":"VRMC_vrm");
    if(legacy) { if(!string(root,"specVersion","0.0").equals("0.0")) throw new IllegalArgumentException("Unsupported VRM 0 version"); }
    else version(root,"VRMC_vrm");
    var bones=new LinkedHashMap<String,Integer>();var humanoid=object(root,"humanoid");
    if(legacy) for(var value:array(humanoid,"humanBones")) {
      var b=value.getAsJsonObject();putBone(bones,string(b,"bone",""),integer(b,"node",-1),scene);
    } else for(var entry:object(humanoid,"humanBones").entrySet())
      putBone(bones,entry.getKey(),integer(entry.getValue().getAsJsonObject(),"node",-1),scene);
    VrmHumanoid.validate(scene,bones,legacy);
    var expressions=legacy?VrmLegacy.expressions(root,scene):expressions(root,scene);
    var constraints=constraints(scene);
    var colliders=new ArrayList<Collider>();var groups=new ArrayList<ColliderGroup>();var springs=new ArrayList<Spring>();var oldSprings=new ArrayList<LegacySpring>();
    if(legacy) VrmLegacy.springs(root,scene,colliders,groups,oldSprings);
    if(ext.has("VRMC_springBone")) readSprings(object(ext,"VRMC_springBone"),scene,colliders,groups,springs);
    var first=legacy?VrmLegacy.firstPerson(root,scene,bones):firstPerson(root,scene,bones);
    var look=legacy?VrmLegacy.lookAt(root):lookAt(root);
    var materials=legacy?VrmLegacy.materials(root,scene):List.<LegacyMaterial>of();
    var features=new ArrayList<CompatibilityReport.Feature>(scene.compatibility().features());
    features.add(new CompatibilityReport.Feature(legacy?"vrm/0":"vrm/1",CompatibilityReport.Level.READ,true,"Versioned avatar, expressions, first-person, gaze, spring and material source profiles"));
    var result=new VrmDocument(gltf,legacy?Version.VRM_0:Version.VRM_1,bones,expressions,constraints,colliders,groups,springs,oldSprings,first,look,materials,
        new CompatibilityReport(features,scene.compatibility().diagnostics()));
    VrmTopology.validateSprings(result);
    new VrmConstraints(scene,constraints); // Validate transitive dependencies before any playback starts.
    return result;
  }
  private static void putBone(Map<String,Integer> bones,String name,int node,SceneAsset scene) {
    index(node,scene.nodes().size(),"humanoid node");if(bones.putIfAbsent(name,node)!=null) throw new IllegalArgumentException("Duplicate human bone: "+name);
  }
  static List<Expression> expressions(JsonObject root,SceneAsset scene) {
    var out=new ArrayList<Expression>();var expressions=object(root,"expressions");var keys=new HashSet<String>();
    for(String group:List.of("preset","custom")) for(var entry:object(expressions,group).entrySet()) {
      String name=entry.getKey();boolean preset=group.equals("preset");
      if(name.isEmpty() || !keys.add(name) || preset!=PRESETS.contains(name)) throw new IllegalArgumentException("Invalid or duplicate expression name: "+name);
      var e=entry.getValue().getAsJsonObject();var morphs=new ArrayList<MorphBind>();var materials=new ArrayList<MaterialBind>();var textures=new ArrayList<TextureBind>();
      for(var v:array(e,"morphTargetBinds")) {
        var b=v.getAsJsonObject();int n=index(integer(b,"node",-1),scene.nodes().size(),"morph node"),m=integer(b,"index",-1);
        checkMorph(scene,n,m);morphs.add(new MorphBind(n,m,range(number(b,"weight",Float.NaN),0,1,"morph weight")));
        if(!b.has("weight")) throw new IllegalArgumentException("Missing morph weight");
      }
      for(var v:array(e,"materialColorBinds")) {
        var b=v.getAsJsonObject();int m=index(integer(b,"material",-1),scene.materials().size(),"material");String type=string(b,"type","");
        if(!Set.of("color","emissionColor","shadeColor","matcapColor","rimColor","outlineColor").contains(type)) throw new IllegalArgumentException("Invalid material color type");
        materials.add(new MaterialBind(m,type,vector(b,"targetValue",4)));
      }
      for(var v:array(e,"textureTransformBinds")) {
        var b=v.getAsJsonObject();textures.add(new TextureBind(index(integer(b,"material",-1),scene.materials().size(),"material"),vector(b,"scale",2,1,1),vector(b,"offset",2,0,0)));
      }
      out.add(new Expression(name,name,preset?name:"",bool(e,"isBinary",false),override(e,"overrideBlink"),override(e,"overrideMouth"),override(e,"overrideLookAt"),morphs,materials,textures));
    }return List.copyOf(out);
  }
  static final Set<String> PRESETS=Set.of("happy","angry","sad","relaxed","surprised","aa","ih","ou","ee","oh","blink","blinkLeft","blinkRight","lookUp","lookDown","lookLeft","lookRight","neutral");
  private static VrmDocument.Override override(JsonObject o,String key) { return switch(string(o,key,"none")) {
    case "none" -> VrmDocument.Override.NONE;case "block" -> VrmDocument.Override.BLOCK;case "blend" -> VrmDocument.Override.BLEND;
    default -> throw new IllegalArgumentException("Invalid expression override");
  }; }
  static void checkMorph(SceneAsset scene,int node,int morph) {
    int mesh=scene.nodes().get(node).mesh();index(mesh,scene.meshes().size(),"morph mesh");
    for(var primitive:scene.meshes().get(mesh).primitives()) index(morph,primitive.morphs().size(),"morph");
  }
  public static List<Constraint> constraints(SceneAsset scene) {
    var out=new ArrayList<Constraint>();for(int n=0;n<scene.nodes().size();n++) {
      var ext=extensions(scene.nodes().get(n).metadata());if(!ext.has("VRMC_node_constraint")) continue;
      var e=object(ext,"VRMC_node_constraint");version(e,"VRMC_node_constraint");var c=object(e,"constraint");
      int count=0;for(String k:List.of("rotation","roll","aim")) if(c.has(k)) count++;
      if(count!=1) throw new IllegalArgumentException("Constraint requires exactly one operation");
      String kind=c.has("rotation")?"rotation":c.has("roll")?"roll":"aim";var p=object(c,kind);
      int source=index(integer(p,"source",-1),scene.nodes().size(),"constraint source");if(source==n) throw new IllegalArgumentException("Constraint targets itself");
      Vec3 axis=switch(kind) {
        case "rotation" -> Vec3.ZERO;
        case "roll" -> axis(string(p,"rollAxis",""),false);
        default -> axis(string(p,"aimAxis",""),true);
      };
      out.add(new Constraint(n,source,ConstraintType.valueOf(kind.toUpperCase(Locale.ROOT)),axis,range(number(p,"weight",1),0,1,"constraint weight")));
    }return List.copyOf(out);
  }
  private static Vec3 axis(String axis,boolean signed) {
    String value=axis;float sign=1;if(signed) {
      if(value.startsWith("Positive")) value=value.substring(8);
      else if(value.startsWith("Negative")) { value=value.substring(8);sign=-1; }
      else throw new IllegalArgumentException("Invalid aim axis");
    }return switch(value) { case "X"->new Vec3(sign,0,0);case "Y"->new Vec3(0,sign,0);case "Z"->new Vec3(0,0,sign);default->throw new IllegalArgumentException("Invalid axis"); };
  }
  static void readSprings(JsonObject s,SceneAsset scene,List<Collider> colliders,List<ColliderGroup> groups,List<Spring> springs) {
    version(s,"VRMC_springBone");if(!colliders.isEmpty() || !groups.isEmpty()) throw new IllegalArgumentException("Mixed VRM 0 and VRM 1 spring profiles");
    for(var value:array(s,"colliders")) {
      var c=value.getAsJsonObject();var shapes=object(c,"shape");if(shapes.has("sphere")==shapes.has("capsule")) throw new IllegalArgumentException("Expected one collider shape");
      boolean capsule=shapes.has("capsule");var shape=object(shapes,capsule?"capsule":"sphere");
      colliders.add(new Collider(index(integer(c,"node",-1),scene.nodes().size(),"collider node"),vec3(shape,"offset",Vec3.ZERO),capsule?vec3(shape,"tail",Vec3.ZERO):null,
          range(number(shape,"radius",0),0,Float.MAX_VALUE,"radius")));
    }
    for(var value:array(s,"colliderGroups")) { var g=value.getAsJsonObject();groups.add(new ColliderGroup(string(g,"name",""),indices(array(g,"colliders"),colliders.size(),"collider"))); }
    for(var value:array(s,"springs")) {
      var s0=value.getAsJsonObject();var joints=new ArrayList<Joint>();for(var j:array(s0,"joints")) joints.add(joint(j.getAsJsonObject(),scene));
      if(joints.isEmpty()) throw new IllegalArgumentException("Spring has no joints");
      springs.add(new Spring(string(s0,"name",""),optionalIndex(integer(s0,"center",-1),scene.nodes().size(),"spring center"),joints,indices(array(s0,"colliderGroups"),groups.size(),"collider group")));
    }
  }
  private static Joint joint(JsonObject j,SceneAsset scene) {
    var dir=vec3(j,"gravityDir",new Vec3(0,-1,0));
    return new Joint(index(integer(j,"node",-1),scene.nodes().size(),"spring joint"),range(number(j,"hitRadius",0),0,Float.MAX_VALUE,"hitRadius"),
        range(number(j,"stiffness",1),0,Float.MAX_VALUE,"stiffness"),range(number(j,"gravityPower",0),0,Float.MAX_VALUE,"gravityPower"),dir,range(number(j,"dragForce",.5f),0,1,"dragForce"));
  }
  private static FirstPerson firstPerson(JsonObject root,SceneAsset scene,Map<String,Integer> bones) {
    var out=new ArrayList<MeshAnnotation>();var seen=new HashSet<Integer>();for(var value:array(object(root,"firstPerson"),"meshAnnotations")) {
      var a=value.getAsJsonObject();int n=index(integer(a,"node",-1),scene.nodes().size(),"first-person node");
      if(scene.nodes().get(n).mesh()==-1 || !seen.add(n)) throw new IllegalArgumentException("Invalid first-person annotation");
      String type=string(a,"type","");if(!Set.of("auto","both","thirdPersonOnly","firstPersonOnly").contains(type)) throw new IllegalArgumentException("Invalid first-person type");out.add(new MeshAnnotation(n,type));
    }return new FirstPerson(bones.get("head"),Vec3.ZERO,out);
  }
  private static LookAt lookAt(JsonObject root) {
    if(!root.has("lookAt")) return null;var l=object(root,"lookAt");String type=string(l,"type","bone");
    if(!Set.of("bone","expression").contains(type)) throw new IllegalArgumentException("Invalid lookAt type");
    float output=type.equals("expression")?1:10;
    return new LookAt(type,vec3(l,"offsetFromHeadBone",new Vec3(0,.06f,0)),rangeMap(l,"rangeMapHorizontalInner",output),rangeMap(l,"rangeMapHorizontalOuter",output),rangeMap(l,"rangeMapVerticalDown",output),rangeMap(l,"rangeMapVerticalUp",output));
  }
  private static RangeMap rangeMap(JsonObject l,String name,float output) {
    var r=object(l,name);return new RangeMap(range(number(r,"inputMaxValue",90),0,180,"inputMaxValue"),number(r,"outputScale",output),FloatData.EMPTY);
  }
}
