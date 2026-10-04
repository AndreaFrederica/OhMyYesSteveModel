package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.physics.*;
import java.nio.*;
import java.util.*;

/** Optional RS-style movement policy, independently usable before player/host integration.
 * Placement uses right-handed world units; Bullet uses original left-handed PMX units.
 * The caller syncs animated bodies, calls beforeStep, steps once, then calls afterStep.
 */
public final class MmdPhysicsMotion {
  public record Body(int id,float mass) {
    public Body { if(id<0 || !Float.isFinite(mass) || mass<=0)throw new IllegalArgumentException("Expected a dynamic body and positive mass"); }
  }
  public record Settings(float stepSeconds,float inertiaStrength,float maxWorldSpeed,float maxLinearSpeed,float maxAngularSpeed) {
    public Settings {
      if(!Float.isFinite(stepSeconds) || stepSeconds<=0 || stepSeconds>1 || !Float.isFinite(inertiaStrength) || inertiaStrength<0 ||
          !Float.isFinite(maxWorldSpeed) || maxWorldSpeed<=0 || !Float.isFinite(maxLinearSpeed) || maxLinearSpeed<=0 || !Float.isFinite(maxAngularSpeed) || maxAngularSpeed<=0)
        throw new IllegalArgumentException("Invalid MMD motion policy");
    }
  }
  public record Input(Vec3 worldPosition,Rotation modelToWorld,float modelUnitsPerWorldUnit,float elapsedSeconds,Vec3 worldGravity) {
    public Input {
      Objects.requireNonNull(worldPosition);Objects.requireNonNull(modelToWorld);Objects.requireNonNull(worldGravity);
      if(!Float.isFinite(modelUnitsPerWorldUnit) || modelUnitsPerWorldUnit<=0 || !Float.isFinite(elapsedSeconds) || elapsedSeconds<=0)
        throw new IllegalArgumentException("Invalid placement/time units");
    }
  }
  private final PhysicsWorld world;
  private final Settings settings;
  private final List<Body> bodies;
  private final IntBuffer ids;
  private final FloatBuffer forces;
  private Vec3 previousPosition,gravity;

  public MmdPhysicsMotion(PhysicsWorld world,Settings settings,List<Body> bodies) {
    this.world=Objects.requireNonNull(world);this.settings=Objects.requireNonNull(settings);this.bodies=List.copyOf(bodies);
    if(bodies.size()>65536)throw new IllegalArgumentException("Motion body budget exceeded");
    ids=ByteBuffer.allocateDirect(bodies.size()*4).order(ByteOrder.nativeOrder()).asIntBuffer();
    forces=ByteBuffer.allocateDirect(bodies.size()*28).order(ByteOrder.nativeOrder()).asFloatBuffer();
    var seen=new HashSet<Integer>();for(int i=0;i<bodies.size();i++){var body=bodies.get(i);if(!seen.add(body.id()))throw new IllegalArgumentException("Duplicate motion body");ids.put(i,body.id());}
  }
  private static Vec3 limited(Vec3 value,double maximum) {
    double length=Math.sqrt(value.dot(value));return length<=maximum?value:value.multiply((float)(maximum/length));
  }
  private static Vec3 sourceVector(Vec3 world,Input input) {
    var local=input.modelToWorld().inverse().rotate(world).multiply(input.modelUnitsPerWorldUnit());return new Vec3(local.x(),local.y(),-local.z());
  }
  public void beforeStep(Input input) {
    Objects.requireNonNull(input);
    var nextGravity=sourceVector(input.worldGravity(),input);
    Vec3 acceleration=Vec3.ZERO;
    if(previousPosition!=null && settings.inertiaStrength()>0) {
      double dt=Math.max(input.elapsedSeconds(),.001);
      // Compute differences in double, so a finite large teleport still clamps safely.
      double x=((double)input.worldPosition().x()-previousPosition.x())/dt;
      double y=((double)input.worldPosition().y()-previousPosition.y())/dt;
      double z=((double)input.worldPosition().z()-previousPosition.z())/dt;
      double speed=Math.hypot(Math.hypot(x,y),z),scale=speed>settings.maxWorldSpeed()?settings.maxWorldSpeed()/speed:1;
      var velocity=sourceVector(new Vec3((float)(x*scale),(float)(y*scale),(float)(z*scale)),input);
      // F = mass * (-model velocity * strength / elapsed), capped to one step's speed budget.
      acceleration=limited(velocity.multiply((float)(-settings.inertiaStrength()/dt)),settings.maxLinearSpeed()/settings.stepSeconds());
    }
    // Fill/validate the whole transfer before changing the world or movement history.
    for(int i=0;i<bodies.size();i++) {
      float mass=bodies.get(i).mass(),x=acceleration.x()*mass,y=acceleration.y()*mass,z=acceleration.z()*mass;int offset=i*7;
      if(!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))throw new IllegalArgumentException("Motion force overflow");
      forces.put(offset,x).put(offset+1,y).put(offset+2,z);
    }
    if(!nextGravity.equals(gravity)){world.setGravity(nextGravity);gravity=nextGravity;}
    if(!bodies.isEmpty() && acceleration.dot(acceleration)>0)world.applyForces(ids,forces);
    previousPosition=input.worldPosition();
  }
  public void afterStep() { if(!bodies.isEmpty())world.clampVelocities(ids,settings.maxLinearSpeed(),settings.maxAngularSpeed()); }
  /** Rebuild/teleport owners may explicitly discard movement history without resetting the world. */
  public void resetMotion() { previousPosition=null; }
}
