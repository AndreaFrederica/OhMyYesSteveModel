package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.gltf.GltfReader;
import cc.sirrus.ysmlib.scene.io.*;
import java.io.IOException;
import java.util.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmJson.*;

public final class VrmaReader {
  public VrmaDocument read(ByteData source,AssetResolver resolver,ReadLimits limits) throws IOException { return profile(new GltfReader().read(source,resolver,limits)); }
  public VrmaDocument profile(GltfDocument gltf) throws AssetFormatException {
    try {
      var scene=gltf.scene();var ext=extensions(scene.metadata());if(!ext.has("VRMC_vrm_animation")) throw new IllegalArgumentException("No VRM animation extension");
      var root=object(ext,"VRMC_vrm_animation");version(root,"VRMC_vrm_animation");var bones=new LinkedHashMap<String,Integer>();
      for(var entry:object(object(root,"humanoid"),"humanBones").entrySet()) bones.put(entry.getKey(),index(integer(entry.getValue().getAsJsonObject(),"node",-1),scene.nodes().size(),"animation human bone"));
      if(root.has("humanoid")) VrmHumanoid.validate(scene,bones,false);
      var expressions=new LinkedHashMap<String,Integer>();var e=object(root,"expressions");
      for(String group:List.of("preset","custom")) for(var entry:object(e,group).entrySet()) {
        String name=entry.getKey();if(name.isEmpty() || group.equals("preset")!=VrmReader.PRESETS.contains(name)) throw new IllegalArgumentException("Invalid VRMA expression name");
        int node=index(integer(entry.getValue().getAsJsonObject(),"node",-1),scene.nodes().size(),"animation expression");
        if(expressions.putIfAbsent(name,node)!=null) throw new IllegalArgumentException("Duplicate VRMA expression");
      }
      var look=object(root,"lookAt");int gaze=optionalIndex(integer(look,"node",-1),scene.nodes().size(),"animation gaze");Vec3 offset=vec3(look,"offsetFromHeadBone",Vec3.ZERO);
      var names=new HashMap<Integer,String>();bones.forEach((k,v)->names.put(v,k));
      var forbiddenExpressionNodes=new HashSet<Integer>();for(String key:List.of("lookUp","lookDown","lookLeft","lookRight")) if(expressions.containsKey(key)) forbiddenExpressionNodes.add(expressions.get(key));
      for(var clip:scene.animations()) for(var track:clip.tracks()) {
        String bone=names.get(track.targetIndex());
        if(bone!=null) {
          if(bone.equals("leftEye") || bone.equals("rightEye")) throw new IllegalArgumentException("VRMA eye animation must use LookAt");
          if(track.property()==AnimationClip.Property.SCALE || track.property()==AnimationClip.Property.TRANSLATION && !bone.equals("hips")) throw new IllegalArgumentException("Forbidden humanoid animation channel: "+bone);
        }
        if(forbiddenExpressionNodes.contains(track.targetIndex())) throw new IllegalArgumentException("VRMA gaze expressions must use LookAt");
      }
      var features=new ArrayList<>(scene.compatibility().features());features.add(new CompatibilityReport.Feature("vrma/1",CompatibilityReport.Level.READ,true,"Humanoid, expressions, gaze and all clips retained; target-specific retargeting is a separate operation"));
      return new VrmaDocument(gltf,bones,expressions,gaze,offset,new CompatibilityReport(features,scene.compatibility().diagnostics()));
    } catch(IllegalArgumentException|IllegalStateException|ClassCastException|ArithmeticException error) { throw new AssetFormatException("Invalid VRMA: "+error.getMessage(),error); }
  }
}
