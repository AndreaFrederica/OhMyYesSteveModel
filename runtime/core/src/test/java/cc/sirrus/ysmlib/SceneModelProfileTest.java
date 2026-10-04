package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneModelProfileTest {
  @Test void malformedProfileFieldsCannotSilentlyFallBackToDefaults() {
    var bytes=YsmRuntime.scenes().writeModelProfile(SceneModelProfile.defaults(.08,18,0));
    var json=new String(bytes.copy(),java.nio.charset.StandardCharsets.UTF_8);
    for(String malformed:List.of(json.replace("\"footY\"","\"footTypo\""),json.replace("\"outlines\": true,",""),json.replace("\"schemaVersion\": 1","\"schemaVersion\": null"))) {
      assertNotEquals(json,malformed);
      assertThrows(IllegalArgumentException.class,()->YsmRuntime.scenes().readModelProfile(new ByteData(malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
  }
  @Test void profileRoundTripsWithoutPreviewPhysicsAndPreservesOriginalNames() {
    var original=SceneModelProfile.defaults(.08,18.35,-.0045).withBones(Map.of("leftLegIk",new SceneModelProfile.BoneBinding(3,"左足ＩＫ","全ての親",0,0,0,1)));
    var bytes=YsmRuntime.scenes().writeModelProfile(original);
    assertEquals(original,YsmRuntime.scenes().readModelProfile(bytes));
    assertFalse(new String(bytes.copy(),java.nio.charset.StandardCharsets.UTF_8).contains("physicsEnabled"));
    assertEquals(1.468,original.placement().actualHeight(),1e-6);
  }
  @Test void invalidScaleAndStaleBoneIdentityFailInsteadOfSilentlyRebinding() {
    assertThrows(IllegalArgumentException.class,()->SceneModelProfile.defaults(0,1,0));
    assertThrows(IllegalArgumentException.class,()->SceneModelProfile.defaults(Double.NaN,1,0));
    var bones=List.of(new SceneSkeleton.Bone(0,"head","",-1,Vec3.ZERO,false));
    assertThrows(IllegalArgumentException.class,()->SceneSkeleton.validate(bones,Map.of("head",new SceneModelProfile.BoneBinding(0,"other","",0,0,0,1))));
    assertThrows(IllegalArgumentException.class,()->SceneSkeleton.validate(bones,Map.of("leftLegIk",SceneSkeleton.bind(bones,0))));
  }
  @Test void suggestionsKeepIkSeparateAndRefuseAmbiguousNames() {
    var bones=List.of(new SceneSkeleton.Bone(0,"左足","",-1,Vec3.ZERO,false),new SceneSkeleton.Bone(1,"左足ＩＫ","",-1,Vec3.ZERO,true),
        new SceneSkeleton.Bone(2,"head","",-1,Vec3.ZERO,false),new SceneSkeleton.Bone(3,"頭","",-1,Vec3.ZERO,false));
    var map=SceneSkeleton.suggest(bones);assertEquals(0,map.get("leftUpperLeg").index());assertEquals(1,map.get("leftLegIk").index());assertFalse(map.containsKey("head"));
  }
  @Test void heightModeUsesOneScaleAndStableActionPathsSurviveIdReordering() {
    var p=new SceneModelProfile.Placement(.08,SceneModelProfile.SizeMode.HEIGHT,2,1.8,18,0,0,0,0,0);
    assertEquals(.1,p.effectiveScale(),1e-9);assertEquals(1.8,p.actualHeight(),1e-9);
    var source=new ScenePackage(new ScenePackage.Source("model","a.pmx",ScenePackage.Format.PMX),new ScenePackage.Settings(.08,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),
        List.of(new ScenePackage.Source("motion-12","walk.vmd",ScenePackage.Format.VMD)),List.of(),Map.of("a.pmx",new ByteData(new byte[0]),"walk.vmd",new ByteData(new byte[0])));
    assertEquals(new ScenePackagePlayback.Selection("motion-12",0),new SceneModelProfile.Action("walk.vmd",0,true).resolve(source));
  }
}
