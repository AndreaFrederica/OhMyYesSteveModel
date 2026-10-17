package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.Pose;
import cc.sirrus.ysmlib.scene.physics.PhysicsSpec;
import java.util.*;

/** Explicit FBX physics binding. FBX files do not have one portable rigid-body convention. */
public record FbxPhysicsProfile(List<Body> bodies,List<Joint> joints) {
  public static final String PACKAGE_PATH="_omysm/fbx-physics.json";
  public record Body(String id,int node,PhysicsSpec.Body spec) {
    public Body {
      if(Objects.requireNonNull(id).isBlank()||id.length()>128)throw new IllegalArgumentException("Invalid FBX physics body id");
      if(node<0)throw new IllegalArgumentException("Invalid FBX physics node");Objects.requireNonNull(spec);
    }
  }
  /** bodyA/bodyB refer to indices in bodies; -1 means the fixed world. */
  public record Joint(int bodyA,int bodyB,PhysicsSpec.Joint spec) {
    public Joint {
      if(bodyA< -1||bodyB< -1||bodyA==bodyB)throw new IllegalArgumentException("Invalid FBX physics joint bodies");
      Objects.requireNonNull(spec);
    }
  }
  public FbxPhysicsProfile {
    bodies=List.copyOf(bodies);joints=List.copyOf(joints);
    var ids=new HashSet<String>();for(var body:bodies)if(!ids.add(body.id()))throw new IllegalArgumentException("Duplicate FBX physics body id: "+body.id());
    for(var joint:joints) {
      if(joint.bodyA()>=bodies.size()||joint.bodyB()>=bodies.size())throw new IllegalArgumentException("FBX physics joint body outside profile");
    }
  }
  public static FbxPhysicsProfile empty(){return new FbxPhysicsProfile(List.of(),List.of());}
}
