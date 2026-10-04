package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.fbx.FbxEvaluation;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenePackageAnimationsTest {
    private final SceneProvider scenes=YsmRuntime.scenes();
    @Test void sourceInventoryKeepsExternalNodeDomainsVisibleWithoutPretendingTheyBind() throws Exception {
        var files=new LinkedHashMap<String,ByteData>();
        for(String name:List.of("SimpleSkin.gltf","SimpleSkin_skinningData.bin","SimpleSkin_inverseBindMatrices.bin","SimpleSkin_geometry.bin","SimpleSkin_animation.bin"))
            files.put(name,resource("/khronos/SimpleSkin/glTF/"+name));
        files.put("separate.gltf",files.get("SimpleSkin.gltf"));
        var source=new ScenePackage(new ScenePackage.Source("model","SimpleSkin.gltf",ScenePackage.Format.GLTF),
                new ScenePackage.Settings(1,0,FbxEvaluation.SkinSpace.BIND_WORLD),
                List.of(new ScenePackage.Source("separate","separate.gltf",ScenePackage.Format.GLTF)),List.of(),files);
        var assets=scenes.loadPackage(source,ReadLimits.DEFAULT);var clips=scenes.animations(assets,48);
        assertEquals(2,clips.size());assertEquals(new ScenePackagePlayback.Selection("model",0),clips.get(0).selection());
        assertEquals(new ScenePackagePlayback.Selection("separate",0),clips.get(1).selection());
        assertEquals(0,clips.get(0).sourceFramesPerSecond());assertEquals(48,clips.get(0).range().framesPerSecond());
        assertThrows(IllegalArgumentException.class,()->scenes.playback(assets,clips.get(1).selection(),ScenePackagePlayback.Settings.preview(),ReadLimits.DEFAULT));
    }
    @Test void fbxRangeRemainsAtOriginalAbsoluteSourceTimeAndRate() throws Exception {
        var source=new ScenePackage(new ScenePackage.Source("model","model.fbx",ScenePackage.Format.FBX),
                new ScenePackage.Settings(1,-1,FbxEvaluation.SkinSpace.BIND_WORLD),List.of(),List.of(),
                Map.of("model.fbx",resource("/ufbx/maya_blend_inbetween_7500_ascii.fbx")));
        var assets=scenes.loadPackage(source,ReadLimits.DEFAULT);var fbx=((ScenePackageAssets.Fbx)assets.model()).value();
        var clips=scenes.animations(assets,60);assertEquals(fbx.animations().size(),clips.size());assertFalse(clips.isEmpty());
        for(int i=0;i<clips.size();i++) {
            assertEquals(fbx.animations().get(i).begin(),clips.get(i).range().start());
            assertEquals(fbx.animations().get(i).end(),clips.get(i).range().end());
            assertEquals(fbx.info().framesPerSecond(),clips.get(i).sourceFramesPerSecond());
        }
    }
    @Test void vmdAndPoseKeepSourceRateAndStillPoseRange() throws Exception {
        var vpd="Vocaloid Pose Data file\n\nmodel.osm;\n1;\nBone0{center\n0,0,0;\n0,0,0,1;\n}\n";
        var source=new ScenePackage(new ScenePackage.Source("model","model.pmx",ScenePackage.Format.PMX),
                new ScenePackage.Settings(.08,-1,FbxEvaluation.SkinSpace.BIND_WORLD),
                List.of(new ScenePackage.Source("motion","motion.vmd",ScenePackage.Format.VMD),new ScenePackage.Source("pose","pose.vpd",ScenePackage.Format.VPD)),List.of(),
                Map.of("model.pmx",resource("/mmd-oracle/skin.pmx"),"motion.vmd",resource("/mmd-oracle/skin.vmd"),"pose.vpd",new ByteData(vpd.getBytes(StandardCharsets.UTF_8))));
        var clips=scenes.animations(scenes.loadPackage(source,ReadLimits.DEFAULT),60);
        assertEquals(9,clips.size());
        assertEquals("motion",clips.get(0).selection().sourceId());assertEquals("pose",clips.get(1).selection().sourceId());
        assertTrue(clips.subList(2,9).stream().allMatch(c->c.selection().sourceId().startsWith("@ysm/generated/")));
        assertEquals(30,clips.get(0).sourceFramesPerSecond());assertEquals(30,clips.get(0).range().framesPerSecond());
        assertEquals(0,clips.get(1).range().start());assertEquals(0,clips.get(1).range().end());
        assertThrows(IllegalArgumentException.class,()->scenes.animations(scenes.loadPackage(source,ReadLimits.DEFAULT),0));
    }
    private ByteData resource(String path) throws Exception {
        try(var input=getClass().getResourceAsStream(path)) { return new ByteData(Objects.requireNonNull(input,path).readAllBytes()); }
    }
}
