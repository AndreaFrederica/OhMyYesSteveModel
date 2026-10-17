package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.fbx.FbxPhysicsProfile;
import cc.sirrus.ysmlib.scene.physics.PhysicsSpec;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict bounded codec for the explicit FBX physics sidecar. */
public final class FbxPhysicsProfileCodec {
  private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
  public FbxPhysicsProfile read(ByteData bytes) {
    if(bytes.size()>1024*1024)throw new IllegalArgumentException("FBX physics profile exceeds 1 MiB");
    var root=object(JsonParser.parseString(new String(bytes.copy(),StandardCharsets.UTF_8)),"profile");keys(root,"profile","bodies","joints");
    var bodies=new ArrayList<FbxPhysicsProfile.Body>();var bodyValues=array(root,"bodies");
    for(var value:bodyValues){var o=object(value,"body");keys(o,"body","id","node","shape","dimensions","pose","motion","mass","linearDamping","angularDamping","restitution","friction","margin","collisionGroup","collisionMask");
      bodies.add(new FbxPhysicsProfile.Body(text(o,"id"),integer(o,"node"),new PhysicsSpec.Body(PhysicsSpec.Shape.valueOf(text(o,"shape")),vec(o,"dimensions"),pose(o,"pose"),PhysicsSpec.Motion.valueOf(text(o,"motion")),number(o,"mass"),number(o,"linearDamping"),number(o,"angularDamping"),number(o,"restitution"),number(o,"friction"),number(o,"margin"),integer(o,"collisionGroup"),integer(o,"collisionMask"))));}
    var joints=new ArrayList<FbxPhysicsProfile.Joint>();for(var value:array(root,"joints")){var o=object(value,"joint");keys(o,"joint","bodyA","bodyB","type","frameA","frameB","linearLower","linearUpper","angularLower","angularUpper","linearStiffness","angularStiffness","springDamping","disableLinkedCollision");
      var spec=new PhysicsSpec.Joint(PhysicsSpec.JointType.valueOf(text(o,"type")),0,-1,pose(o,"frameA"),pose(o,"frameB"),vec(o,"linearLower"),vec(o,"linearUpper"),vec(o,"angularLower"),vec(o,"angularUpper"),vec(o,"linearStiffness"),vec(o,"angularStiffness"),number(o,"springDamping"),bool(o,"disableLinkedCollision"));
      joints.add(new FbxPhysicsProfile.Joint(integer(o,"bodyA"),integer(o,"bodyB"),spec));}
    return new FbxPhysicsProfile(bodies,joints);
  }
  public ByteData write(FbxPhysicsProfile profile){
    var root=new JsonObject();var bodies=new JsonArray();for(var body:profile.bodies()){var o=new JsonObject();o.addProperty("id",body.id());o.addProperty("node",body.node());var b=body.spec();o.addProperty("shape",b.shape().name());o.add("dimensions",vec(b.dimensions()));o.add("pose",pose(b.pose()));o.addProperty("motion",b.motion().name());o.addProperty("mass",b.mass());o.addProperty("linearDamping",b.linearDamping());o.addProperty("angularDamping",b.angularDamping());o.addProperty("restitution",b.restitution());o.addProperty("friction",b.friction());o.addProperty("margin",b.margin());o.addProperty("collisionGroup",b.collisionGroup());o.addProperty("collisionMask",b.collisionMask());bodies.add(o);}root.add("bodies",bodies);
    var joints=new JsonArray();for(var joint:profile.joints()){var o=new JsonObject();o.addProperty("bodyA",joint.bodyA());o.addProperty("bodyB",joint.bodyB());var j=joint.spec();o.addProperty("type",j.type().name());o.add("frameA",pose(j.frameA()));o.add("frameB",pose(j.frameB()));o.add("linearLower",vec(j.linearLower()));o.add("linearUpper",vec(j.linearUpper()));o.add("angularLower",vec(j.angularLower()));o.add("angularUpper",vec(j.angularUpper()));o.add("linearStiffness",vec(j.linearStiffness()));o.add("angularStiffness",vec(j.angularStiffness()));o.addProperty("springDamping",j.springDamping());o.addProperty("disableLinkedCollision",j.disableLinkedCollision());joints.add(o);}root.add("joints",joints);
    return new ByteData(JSON.toJson(root).getBytes(StandardCharsets.UTF_8));
  }
  private static JsonObject object(JsonElement e,String where){if(e==null||!e.isJsonObject())throw new IllegalArgumentException("Expected object: "+where);return e.getAsJsonObject();}
  private static JsonArray array(JsonObject o,String name){var e=o.get(name);if(e==null||!e.isJsonArray())throw new IllegalArgumentException("Expected array: profile."+name);return e.getAsJsonArray();}
  private static void keys(JsonObject o,String where,String... names){var allowed=Set.of(names);for(var key:o.keySet())if(!allowed.contains(key))throw new IllegalArgumentException("Unknown field: "+where+"."+key);for(String name:names)if(!o.has(name))throw new IllegalArgumentException("Missing field: "+where+"."+name);}
  private static String text(JsonObject o,String n){return o.get(n).getAsString();}private static int integer(JsonObject o,String n){return o.get(n).getAsInt();}private static float number(JsonObject o,String n){float v=o.get(n).getAsFloat();if(!Float.isFinite(v))throw new IllegalArgumentException("Non-finite FBX physics value: "+n);return v;}private static boolean bool(JsonObject o,String n){return o.get(n).getAsBoolean();}
  private static Vec3 vec(JsonObject o,String n){var a=o.getAsJsonArray(n);if(a.size()!=3)throw new IllegalArgumentException("Expected vec3: "+n);return new Vec3(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());}
  private static Pose pose(JsonObject o,String n){var a=o.getAsJsonArray(n);if(a.size()!=7)throw new IllegalArgumentException("Expected pose7: "+n);return new Pose(new Vec3(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat()),Rotation.normalized(a.get(3).getAsFloat(),a.get(4).getAsFloat(),a.get(5).getAsFloat(),a.get(6).getAsFloat()));}
  private static JsonArray vec(Vec3 v){var a=new JsonArray();a.add(v.x());a.add(v.y());a.add(v.z());return a;}
  private static JsonArray pose(Pose p){var a=new JsonArray();a.add(p.position().x());a.add(p.position().y());a.add(p.position().z());a.add(p.rotation().x());a.add(p.rotation().y());a.add(p.rotation().z());a.add(p.rotation().w());return a;}
}
