package com.elfmcys.ysm.client.animation;

import cc.sirrus.ysmlib.*;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.format.parser.AnimationBuilder;
import com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile;
import com.elfmcys.ysm.geckolib3.core.keyframe.bone.BoneKeyFrame;
import com.elfmcys.ysm.model.resource.client.render.AnimationProtoMapper;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;
import java.nio.file.*;
import java.util.*;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real built-in curve -> production keyframe evaluator -> binding -> production MMD playback.
 * This covers authored limb curves, not Minecraft controller selection or host query emulation. */
class BuiltinYsmMmdCurveTest {
    @Test void originalWalkAndRunCurvesDeformMmdWithoutGeneratedAnimation()throws Exception {
        AnimationFile file;
        try(var reader=Files.newBufferedReader(Path.of("src/main/resources/assets/ysm/builtin/default/animations/main.animation.json"))) {
            file=AnimationFile.createGson(false).fromJson(reader,AnimationFile.class);
        }
        var archive=new ScenePackage(new ScenePackage.Source("model","skin.pmx",ScenePackage.Format.PMX),
            new ScenePackage.Settings(.08,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),List.of(),List.of(),
            Map.of("skin.pmx",new ByteData(Files.readAllBytes(Path.of("runtime/java-mmd/src/test/resources/mmd-oracle/skin.pmx")))));
        var assets=YsmRuntime.scenes().loadPackage(archive,ReadLimits.DEFAULT);
        var profile=SceneModelProfile.defaults(.08,20,0).withBones(Map.of("bone:leg",SceneSkeleton.bind(SceneSkeleton.of(assets),0)))
            .withRetarget(new SceneModelProfile.Retarget(Map.of("bone:leg","LeftLeg"),.45));
        var binding=new YsmSkeletonBinding(List.of(new YsmSkeletonBinding.Bone("LeftLeg",-1,Vec3.ZERO,Vec3.ZERO)),assets,profile);
        var sampled=new ArrayList<Map<Integer,Pose>>();
        for(String name:List.of("walk","run")) {
            var proto=AnimationBuilder.build(file).animations().stream().filter(a->a.name().equals(name)).findFirst().orElseThrow();
            var animation=AnimationProtoMapper.animation(proto);
            var limb=animation.boneAnimations.stream().filter(b->b.boneName.equals("LeftLeg")).findFirst().orElseThrow();
            try(var player=YsmRuntime.scenes().playback(assets,ScenePackagePlayback.Selection.REST,ScenePackagePlayback.Settings.previewUi().withPhysics(false),ReadLimits.DEFAULT)) {
                var rest=player.seek(0);var states=new HashSet<Map<Integer,Pose>>();
                for(float fraction:new float[]{.1f,.3f,.6f}) {
                    float[] attributes=new float[14];attributes[6]=attributes[7]=attributes[8]=1;
                    var angles=sample(limb.rotationKeyFrames,animation.animationLength*fraction);
                    attributes[0]=angles.x;attributes[1]=angles.y;attributes[2]=angles.z;
                    var position=sample(limb.positionKeyFrames,animation.animationLength*fraction);
                    attributes[3]=position.x;attributes[4]=position.y;attributes[5]=position.z;
                    var pose=binding.evaluate(attributes);states.add(pose);sampled.add(pose);player.bonePoses(pose);
                    assertNotEquals(rest.thirdPerson(),player.seek(fraction).thirdPerson(),name+" must deform real MMD geometry");
                }
                assertTrue(states.size()>1,name+" must follow its authored time curve");
            }
        }
        assertNotEquals(sampled.get(0),sampled.get(3),"walk/run must retain different authored poses");
    }
    private Vector3f sample(List<BoneKeyFrame> frames,float tick) {
        if(frames.isEmpty())return new Vector3f();
        var frame=frames.stream().filter(f->f.getEndTick()>=tick).findFirst().orElse(frames.get(frames.size()-1));
        float fraction=frame.getTotalTick()==0?1:Math.max(0,Math.min(1,(tick-frame.getStartTick())/frame.getTotalTick()));
        return frame.getLerpPoint(ExpressionEvaluator.evaluator(),fraction);
    }
}
