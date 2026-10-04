package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cc.sirrus.ysmlib.scene.mmd.MmdRigTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PmdRuntimeTest {
  private PmdDocument source() throws Exception { try(var in=getClass().getResourceAsStream("/mmd-oracle/pmd.pmd")) { return new PmdReader().read(new ByteData(Objects.requireNonNull(in).readAllBytes()),ReadLimits.DEFAULT); } }
  private static PmdDocument change(PmdDocument d,List<PmdDocument.Bone> bones,List<PmdDocument.Ik> ik,List<PmxDocument.RigidBody> bodies) {
    return new PmdDocument(d.name(),d.comment(),d.vertices(),d.indices(),d.materials(),bones,ik,d.morphs(),d.morphDisplay(),d.frameNames(),d.boneDisplay(),d.english(),d.toonTextures(),bodies,List.of(),d.optionalSections(),d.source());
  }
  @Test void rotationInfluenceRatioAndTwistUseDistinctPmdFields() throws Exception {
    var bones=List.of(new PmdDocument.Bone("driver",65535,1,1,0,Vec3.ZERO),new PmdDocument.Bone("tail",65535,0,0,0,X),
        new PmdDocument.Bone("inherit",65535,1,5,0,Vec3.ZERO),new PmdDocument.Bone("ratio",65535,0,9,50,Vec3.ZERO),new PmdDocument.Bone("twist",65535,1,8,0,Vec3.ZERO));
    var q=Rotation.axisAngle(Z,Math.PI/2);var pose=new MmdEvaluator(change(source(),bones,List.of(),List.of())).evaluate(frame(
        channel(AnimationClip.Property.ROTATION,"driver",q.x(),q.y(),q.z(),q.w()),channel(AnimationClip.Property.ROTATION,"twist",q.x(),q.y(),q.z(),q.w())));
    vector(Y,pose.bones().get(2).rotation().rotate(X));vector(new Vec3((float)Math.sqrt(.5),(float)Math.sqrt(.5),0),pose.bones().get(3).rotation().rotate(X));
    vector(Z,pose.bones().get(4).rotation().rotate(Y));
  }
  @Test void duplicateIkControllerRecordsSurviveWithoutAddingOrRenumberingBones() throws Exception {
    var bones=List.of(new PmdDocument.Bone("root",65535,1,1,0,Vec3.ZERO),new PmdDocument.Bone("tip",0,0,0,0,Y),new PmdDocument.Bone("goal",65535,0,2,0,X));
    var constraint=new PmdDocument.Ik(2,1,30,.1f,new IntData(0));var model=change(source(),bones,List.of(constraint,constraint),List.of());
    var profile=new PmdRuntimeProfile(model);assertEquals(2,profile.constraints.size());assertEquals(3,profile.asset.bones().size());
    vector(X,new MmdEvaluator(model).evaluate(frame()).bones().get(1).position());
    vector(Y,new MmdEvaluator(model).evaluate(frame(channel(AnimationClip.Property.IK_ENABLED,"goal",0))).bones().get(1).position());
  }
  @Test void bodyOffsetsStayBoneRelativeAndPlaybackDoesNotChangeSourceDocument() throws Exception {
    var original=source();var bones=List.of(new PmdDocument.Bone("root",65535,1,1,0,new Vec3(2,0,0)),new PmdDocument.Bone("tip",0,0,1,0,new Vec3(2,1,0)));
    var body=new PmxDocument.RigidBody(names("body"),1,0,0,0,new Vec3(.1f,0,0),new Vec3(0,.25f,0),Vec3.ZERO,1,0,0,0,0,1);
    var model=change(original,bones,List.of(),List.of(body));var profile=new PmdRuntimeProfile(model);
    vector(new Vec3(2,1.25f,0),profile.asset.rigidBodies().get(0).position());vector(new Vec3(0,.25f,0),model.rigidBodies().get(0).position());
    try(var player=new MmdPlayer(model,new AnimationClip("rest",List.of(),30),new WasmPhysicsProvider(),MmdPlayback.Settings.preview(),Map.of())) {
      vector(new Vec3(2,1,0),player.current().pose().bones().get(1).position());var result=player.seek(.5);assertTrue(result.pose().bones().get(1).position().y()<0);
      player.seek(0);assertEquals(result,player.seek(.5));
    }
  }
}
