package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.vrm.VrmDocument;
import com.google.gson.*;
import java.util.*;

/** Defaults derived from source semantics, shared by standalone authoring and game hosts. */
public final class SceneProfiles {
  private SceneProfiles() {}
  public static SceneModelProfile defaults(ScenePackageAssets assets,double height,double foot) {
    var profile=SceneModelProfile.defaults(assets.source().settings().metersPerUnit(),height,foot).withBones(SceneSkeleton.suggest(assets));
    if(!(assets.model() instanceof ScenePackageAssets.Vrm model))return profile;
    var avatar=model.value();var p=profile.placement();boolean legacy=avatar.version()==VrmDocument.Version.VRM_0;
    profile=profile.withPlacement(new SceneModelProfile.Placement(p.metersPerUnit(),p.sizeMode(),p.scale(),p.height(),p.referenceHeight(),p.footY(),p.x(),p.y(),p.z(),legacy?180:0));
    var expressions=new LinkedHashMap<String,SceneModelFeatures.Expression>();
    for(var e:avatar.expressions())expressions.put(e.key(),new SceneModelFeatures.Expression(List.of(new SceneModelFeatures.ExpressionTarget(SceneModelFeatures.Target.VRM_EXPRESSION,-1,-1,e.key(),1)),List.of(),.08,.08));
    profile=viewpoint(avatar,profile);
    var f=profile.features();profile=profile.withFeatures(new SceneModelFeatures(expressions,f.animations(),f.itemRules(),f.layers(),f.socketSpace(),
        new SceneModelFeatures.Procedural(expressions.containsKey("blink"),"blink",4,.15,avatar.lookAt()!=null,"","",20),f.firstPerson()));
    profile=profile.withMetadata(metadata(avatar));
    // Auto blink must have the same phase for every peer and reload of this model.
    String id;try{var hash=java.security.MessageDigest.getInstance("SHA-256");hash.update(assets.source().files().get(assets.source().model().path()).view());id=UUID.nameUUIDFromBytes(hash.digest()).toString();}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    return new SceneModelProfile(profile.schemaVersion(),id,profile.placement(),profile.bones(),profile.actions(),profile.presentation(),profile.metadata(),profile.retarget(),profile.transitions(),profile.heldItems(),profile.hostPhysics(),profile.features());
  }
  private static SceneModelProfile viewpoint(VrmDocument avatar,SceneModelProfile profile) {
    int node=avatar.humanBones().get("head");Vec3 offset=null;
    if(avatar.version()==VrmDocument.Version.VRM_0&&avatar.firstPerson()!=null){
      node=avatar.firstPerson().bone();var v=avatar.firstPerson().offset();offset=new Vec3(v.x(),v.y(),-v.z());
    }else if(avatar.lookAt()!=null)offset=avatar.lookAt().offset();
    if(offset==null){
      var left=avatar.humanBones().get("leftEye");var right=avatar.humanBones().get("rightEye");
      if(left==null||right==null)return profile;var world=new SceneRig(avatar.scene()).restGlobal();
      offset=world.get(node).inverse().transformPoint(SceneRig.position(world.get(left)).add(SceneRig.position(world.get(right))).multiply(.5f));
    }
    String binding=null;for(var entry:profile.bones().entrySet())if(entry.getValue().index()==node){binding=entry.getKey();break;}
    if(binding==null){binding="bone:vrmViewpoint";var bones=new LinkedHashMap<>(profile.bones());
      // Native firstPerson may name a non-humanoid node; preserve its identity without driving it.
      var nodes=avatar.scene().nodes();var rig=new SceneRig(avatar.scene());var path=new ArrayDeque<String>();
      for(int parent=rig.parent(node);parent>=0;parent=rig.parent(parent))path.addFirst(parent+":"+nodes.get(parent).name());
      bones.put(binding,new SceneModelProfile.BoneBinding(node,nodes.get(node).name(),String.join("/",path),0,0,0,1));profile=profile.withBones(bones);
    }
    var f=profile.features();var fp=f.firstPerson();
    fp=new SceneModelFeatures.FirstPerson(fp.hiddenMaterials(),binding,fp.x(),fp.y(),fp.z(),fp.hiddenBones(),fp.boneThreshold(),fp.cameraX(),fp.cameraY(),fp.cameraZ(),offset.x(),offset.y(),offset.z());
    return profile.withFeatures(new SceneModelFeatures(f.expressions(),f.animations(),f.itemRules(),f.layers(),f.socketSpace(),f.procedural(),fp,f.sourceAnimations()));
  }
  public static SceneModelProfile.Metadata metadata(VrmDocument avatar) {
    var extensions=JsonParser.parseString(avatar.scene().metadata().getOrDefault("extensions","{}")).getAsJsonObject();
    boolean legacy=avatar.version()==VrmDocument.Version.VRM_0;var root=extensions.getAsJsonObject(legacy?"VRM":"VRMC_vrm");
    if(root==null||!root.has("meta"))return SceneModelProfile.Metadata.empty();var meta=root.getAsJsonObject("meta");
    var authors=new ArrayList<SceneModelProfile.Author>();if(legacy){String name=string(meta,"author");if(!name.isBlank())authors.add(new SceneModelProfile.Author(name,"author",List.of(),"",""));}
    else if(meta.has("authors"))for(var name:meta.getAsJsonArray("authors"))authors.add(new SceneModelProfile.Author(name.getAsString(),"author",List.of(),"",""));
    var links=new ArrayList<SceneModelProfile.Pair>();for(String key:List.of("licenseUrl","otherLicenseUrl","contactInformation")){
      String value=string(meta,key);if(!value.isBlank()&&links.stream().noneMatch(p->p.key().equals(key)))links.add(new SceneModelProfile.Pair(key,value));}
    String license=string(meta,legacy?"licenseName":"licenseUrl");
    return new SceneModelProfile.Metadata(string(meta,legacy?"title":"name"),string(meta,"copyrightInformation"),new SceneModelProfile.License(license.isBlank()?"All Rights Reserved":license,meta.toString()),authors,links);
  }
  /** VRM 0 meta.texture is a texture index; VRM 1 thumbnailImage is an image index. */
  public static Optional<SceneAsset.Image> thumbnail(VrmDocument avatar) {
    var ext=JsonParser.parseString(avatar.scene().metadata().getOrDefault("extensions","{}")).getAsJsonObject();boolean legacy=avatar.version()==VrmDocument.Version.VRM_0;
    var root=ext.getAsJsonObject(legacy?"VRM":"VRMC_vrm");if(root==null||!root.has("meta"))return Optional.empty();var meta=root.getAsJsonObject("meta");String key=legacy?"texture":"thumbnailImage";if(!meta.has(key))return Optional.empty();
    int index=meta.get(key).getAsBigDecimal().intValueExact();if(legacy&&index==-1)return Optional.empty();
    if(legacy){if(index<0||index>=avatar.scene().textures().size())throw new IllegalArgumentException("VRM thumbnail texture out of range");index=avatar.scene().textures().get(index).image();}
    if(index<0||index>=avatar.scene().images().size())throw new IllegalArgumentException("VRM thumbnail image out of range");return Optional.of(avatar.scene().images().get(index));
  }
  private static String string(JsonObject object,String key){return object.has(key)&&object.get(key).isJsonPrimitive()?object.get(key).getAsString():"";}
}
