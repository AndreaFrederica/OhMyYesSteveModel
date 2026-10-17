package com.elfmcys.ysm.client.animation;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Initial bindings to the shipped YSM player rig. Explicit author profiles always take precedence. */
public final class VrmPlayerDefaults {
    private VrmPlayerDefaults() {}
    public static SceneModelProfile configure(ScenePackageAssets assets,SceneModelProfile profile) {
        var source=new LinkedHashMap<String,String>();
        String[][] pairs={{"hips","AllBody"},{"spine","UpBody"},{"chest","UpperBody"},{"neck","AllHead"},{"head","Head"},
            {"leftUpperArm","LeftArm"},{"leftLowerArm","LeftForeArm"},{"leftHand","LeftHand"},
            {"rightUpperArm","RightArm"},{"rightLowerArm","RightForeArm"},{"rightHand","RightHand"},
            {"leftUpperLeg","LeftLeg"},{"leftLowerLeg","LeftLowerLeg"},{"leftFoot","LeftFoot"},
            {"rightUpperLeg","RightLeg"},{"rightLowerLeg","RightLowerLeg"},{"rightFoot","RightFoot"}};
        for(var pair:pairs)if(profile.bones().containsKey(pair[0]))source.put(pair[0],pair[1]);
        var skeleton=SceneSkeleton.of(assets);double translationScale=1.0/16;
        var hips=profile.bones().get("hips");if(hips!=null){double height=skeleton.get(hips.index()).position().y()-profile.placement().footY();if(height>1e-6)translationScale=height/20.65;}
        profile=profile.withRetarget(new SceneModelProfile.Retarget(source,translationScale));
        // GeoBuilder reflects JSON pivot X (and rotation X/Y); use the baked rig coordinates.
        // VRM 1 faces +Z, so compensate the host scene Y180; VRM 0 already faces -Z.
        var reference=new HashMap<String,Vec3>();
        boolean legacy=assets.model() instanceof ScenePackageAssets.Vrm v&&v.value().version()==cc.sirrus.ysmlib.scene.vrm.VrmDocument.Version.VRM_0;
        for(String side:List.of("Left","Right")){
            int sign=side.equals("Left")?1:-1;var upper=new Vec3(-sign*3.4f,31.025f,0);
            var rotation=Rotation.axisAngle(new Vec3(0,0,1),Math.toRadians(-sign*18));
            reference.put(side+"Arm",upper);
            reference.put(side+"ForeArm",upper.add(rotation.rotate(new Vec3(-sign*.725f,-5.95f,.025f))));
            reference.put(side+"Hand",upper.add(rotation.rotate(new Vec3(-sign*.725f,-11.45f,.025f))));
        }
        if(!legacy)reference.replaceAll((name,p)->new Vec3(-p.x(),p.y(),-p.z()));
        profile=SkeletonReferencePose.arms(reference,skeleton,profile,false);
        var f=profile.features();var fp=f.firstPerson();
        // Match the normal first-person body clearance; keep it explicit and editable in the profile.
        fp=new SceneModelFeatures.FirstPerson(fp.hiddenMaterials(),fp.originBinding(),fp.x(),fp.y(),fp.z(),fp.hiddenBones(),fp.boneThreshold(),fp.cameraX(),fp.cameraY(),.25,fp.originX(),fp.originY(),fp.originZ());
        var layers=new ArrayList<SceneModelFeatures.LayerRule>();
        for(String side:List.of("left","right")){
            var hand=side.equals("left")?SceneModelFeatures.Hand.LEFT:SceneModelFeatures.Hand.RIGHT;
            var arm=List.of(side+"UpperArm",side+"LowerArm",side+"Hand").stream().filter(profile.bones()::containsKey).toList();
            if(arm.isEmpty())continue;
            layers.add(layer(side+"-held",10,new SceneModelFeatures.Match(List.of(),List.of(),List.of("item"),hand,SceneModelFeatures.View.ANY,SceneModelFeatures.ItemState.ANY,""),arm));
            layers.add(layer(side+"-swing",20,new SceneModelFeatures.Match(List.of(),List.of(),List.of(),hand,SceneModelFeatures.View.ANY,SceneModelFeatures.ItemState.SWINGING,""),arm));
        }
        var both=List.of("leftUpperArm","leftLowerArm","leftHand","rightUpperArm","rightLowerArm","rightHand").stream().filter(profile.bones()::containsKey).toList();
        if(!both.isEmpty())layers.add(layer("two-hand-use",30,new SceneModelFeatures.Match(List.of(),List.of(),List.of("bow","crossbow","gun"),SceneModelFeatures.Hand.ANY,SceneModelFeatures.View.ANY,SceneModelFeatures.ItemState.USING,""),both));
        profile=profile.withFeatures(new SceneModelFeatures(f.expressions(),f.animations(),f.itemRules(),layers,SceneModelFeatures.SocketSpace.RETARGET_REFERENCE,f.procedural(),fp,f.sourceAnimations()));
        return profile;
    }
    private static SceneModelFeatures.LayerRule layer(String id,int priority,SceneModelFeatures.Match match,List<String> bones){
        return new SceneModelFeatures.LayerRule(id,priority,match,new SceneModelProfile.Action("@ysm/controller",-1,true),bones,List.of(),1,1,.1,.12,SceneModelFeatures.LayerMode.SOURCE_CLIP);
    }
}
