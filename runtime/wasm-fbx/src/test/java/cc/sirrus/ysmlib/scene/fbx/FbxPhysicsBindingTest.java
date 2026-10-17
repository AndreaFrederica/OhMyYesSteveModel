package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.physics.*;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FbxPhysicsBindingTest {
  private static ByteData sample() throws Exception {try(var in=FbxPhysicsBindingTest.class.getResourceAsStream("/ufbx/maya_transformed_skin_7700_ascii.fbx")){return new ByteData(Objects.requireNonNull(in).readAllBytes());}}
  @Test void explicitSidecarUsesSharedPhysicsWorldAndPublishesNodePose() throws Exception {
    var source=new FbxReader().read(sample(),ReadLimits.DEFAULT);assertFalse(source.nodes().isEmpty());
    var body=new FbxPhysicsProfile.Body("root",0,new PhysicsSpec.Body(PhysicsSpec.Shape.BOX,new Vec3(.1f,.1f,.1f),Pose.IDENTITY,PhysicsSpec.Motion.KINEMATIC,0,0,0,0,.5f,.01f,1,1));
    var profile=new FbxPhysicsProfile(List.of(body),List.of());
    try(var binding=new FbxPhysicsBinding(source,profile,new WasmPhysicsProvider(),PhysicsSpec.World.defaults());
        var player=new FbxPlayer(source,ReadLimits.DEFAULT)) {
      var frame=player.evaluate(-1,0);var poses=binding.step(frame,0);assertTrue(poses.containsKey(0));assertEquals(frame.nodes().size(),source.nodes().size());
      assertEquals(FbxPhysicsBinding.nodePose(frame.nodes().get(0)),poses.get(0));
    }
  }
  @Test void nodePoseReadsUfbxColumnMajorBasis() {
    // +90 degrees around Z in ufbx's compact column-major 3x4 layout.
    var node=new FbxDocument.Node(1,-1,-1,-1,-1,true,0,
        new DoubleData(0,1,0,-1,0,0,0,0,1,0.25,0.5,0.75),
        new DoubleData(0,1,0,-1,0,0,0,0,1,0.25,0.5,0.75),
        DoubleData.EMPTY,DoubleData.EMPTY,IntData.EMPTY);
    var pose=FbxPhysicsBinding.nodePose(node);
    var rotated=pose.rotation().rotate(new Vec3(1,0,0));
    assertEquals(0f,rotated.x(),1e-5f);
    assertEquals(1f,rotated.y(),1e-5f);
    assertEquals(0f,rotated.z(),1e-5f);
    assertEquals(new Vec3(.25f,.5f,.75f),pose.position());
  }
}
