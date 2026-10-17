package com.elfmcys.ysm.client.animation;

import cc.sirrus.ysmlib.*;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VrmPlayerDefaultsTest {
    ScenePackageAssets assets(boolean degenerate)throws Exception{
        var json=JsonParser.parseString(Files.readString(Path.of("runtime/java-vrm/src/test/resources/three-vrm-oracle/retarget-avatar.gltf"))).getAsJsonObject();
        if(degenerate){var human=json.getAsJsonObject("extensions").getAsJsonObject("VRMC_vrm").getAsJsonObject("humanoid").getAsJsonObject("humanBones");int lower=human.getAsJsonObject("leftLowerArm").get("node").getAsInt();json.getAsJsonArray("nodes").get(lower).getAsJsonObject().add("translation",JsonParser.parseString("[0,0,0]"));}
        return YsmRuntime.scenes().loadPackage(new ScenePackage(new ScenePackage.Source("model","avatar.vrm",ScenePackage.Format.VRM),new ScenePackage.Settings(1,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),List.of(),List.of(),Map.of("avatar.vrm",new ByteData(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))),ReadLimits.DEFAULT);
    }
    @Test void defaultsUseActualShippedRigAndPersist()throws Exception{
        var assets=assets(false);var profile=VrmPlayerDefaults.configure(assets,SceneProfiles.defaults(assets,2,0));
        var bones=shippedRig();
        var calibrated=YsmSkeletonBinding.calibrateArms(bones,assets,profile);
        for(String side:List.of("left","right"))for(String part:List.of("UpperArm","LowerArm","Hand")){
            var a=profile.bones().get(side+part).referenceRotation();var b=calibrated.bones().get(side+part).referenceRotation();
            assertEquals(1,Math.abs(a.x()*b.x()+a.y()*b.y()+a.z()*b.z()+a.w()*b.w()),1e-5);
        }
        assertEquals("",profile.features().firstPerson().originBinding());assertEquals(5,profile.features().layers().size());assertEquals(.25,profile.features().firstPerson().cameraZ());
        assertEquals(SceneSkeleton.of(assets).get(profile.bones().get("hips").index()).position().y()/20.65,profile.retarget().translationScale(),1e-7);
        SceneFeatureBindings.validate(assets,profile);assertEquals(profile,YsmRuntime.scenes().readModelProfile(YsmRuntime.scenes().writeModelProfile(profile)));
    }
    @Test void zeroLengthOptionalCalibrationDoesNotPreventLoading()throws Exception{var a=assets(true);assertDoesNotThrow(()->VrmPlayerDefaults.configure(a,SceneProfiles.defaults(a,2,0)));}
    @Test void calibratedSegmentsMatchRenderedSourceAndKeepHandsOutsideTorso()throws Exception {
        var assets=assets(false);var profile=VrmPlayerDefaults.configure(assets,SceneProfiles.defaults(assets,2,0));var bones=shippedRig();
        var rig=new SceneRig(SceneFeatureBindings.scene(assets));var binding=new YsmSkeletonBinding(bones,assets,profile);
        for(float pitch:new float[]{0,.6f}){
            var matrices=new ArrayList<org.joml.Matrix4f>();var positions=new HashMap<String,Vec3>();float[] attributes=new float[bones.size()*14];
            for(int i=0;i<bones.size();i++){
                var bone=bones.get(i);var r=bone.restRotation();var p=bone.pivot();int o=i*14;
                attributes[o]=r.x()+(bone.name().equals("RightArm")?pitch:0);attributes[o+1]=r.y();attributes[o+2]=r.z();attributes[o+6]=attributes[o+7]=attributes[o+8]=1;
                // Independent oracle: the actual renderer uses absolute-pivot conjugation.
                var m=bone.parent()<0?new org.joml.Matrix4f():new org.joml.Matrix4f(matrices.get(bone.parent()));
                m.translate(p.x(),p.y(),p.z()).rotateZYX(attributes[o+2],attributes[o+1],attributes[o]).translate(-p.x(),-p.y(),-p.z());matrices.add(m);
                var v=m.transformPosition(new org.joml.Vector3f(p.x(),p.y(),p.z()));positions.put(bone.name(),new Vec3(-v.x,v.y,-v.z));
            }
            var pose=new ScenePose(0,SceneFeatureBindings.scene(assets).nodes().stream().map(SceneAsset.Node::localMatrix).toList(),rig.restGlobal(),Collections.nCopies(rig.restGlobal().size(),FloatData.EMPTY),new AnimationFrame(0,List.of()));
            var target=rig.deltas(pose,binding.evaluate(attributes)).globalMatrices();
            for(String side:List.of("left","right"))for(String[] segment:List.of(new String[]{"UpperArm","LowerArm"},new String[]{"LowerArm","Hand"})){
                String a=side+segment[0],b=side+segment[1];var expected=positions.get(profile.retarget().sourceBones().get(b)).subtract(positions.get(profile.retarget().sourceBones().get(a))).normalized();
                var actual=SceneRig.position(target.get(profile.bones().get(b).index())).subtract(SceneRig.position(target.get(profile.bones().get(a).index()))).normalized();
                assertEquals(1,expected.dot(actual),1e-5,a+" segment points in rendered source direction");
            }
            if(pitch==0)for(String side:List.of("left","right")){
                var shoulder=SceneRig.position(target.get(profile.bones().get(side+"UpperArm").index()));var hand=SceneRig.position(target.get(profile.bones().get(side+"Hand").index()));
                assertTrue(side.equals("left")?hand.x()>shoulder.x():hand.x()<shoulder.x(),"Hands must point outward, not into the torso");
            }
        }
    }
    static List<YsmSkeletonBinding.Bone> shippedRig()throws Exception {
        var raw=new Gson().fromJson(Files.readString(Path.of("src/main/resources/assets/ysm/builtin/default/models/main.json")),com.elfmcys.ysm.format.parser.pojo.model.GeoModel.class);
        var parsed=com.elfmcys.ysm.format.parser.GeoBuilder.build(raw).model();
        var byName=new LinkedHashMap<String,com.elfmcys.ysm.proto.mixel.asset.model.data.Bone>();
        for(var bone:parsed.bones())byName.put(bone.name(),bone);
        var bones=new ArrayList<YsmSkeletonBinding.Bone>();var indices=new HashMap<String,Integer>();for(String name:byName.keySet())add(name,byName,indices,bones);return bones;
    }
    static void add(String name,Map<String,com.elfmcys.ysm.proto.mixel.asset.model.data.Bone> source,Map<String,Integer> indices,List<YsmSkeletonBinding.Bone> out){
        if(indices.containsKey(name))return;var b=source.get(name);int parent=-1;if(b.parent().isPresent()){String p=b.parent().orElseThrow();add(p,source,indices,out);parent=indices.get(p);}
        indices.put(name,out.size());out.add(new YsmSkeletonBinding.Bone(name,parent,new Vec3(b.pivot().getFloat(0),b.pivot().getFloat(1),b.pivot().getFloat(2)),new Vec3(b.rotate().getFloat(0),b.rotate().getFloat(1),b.rotate().getFloat(2))));
    }
}
