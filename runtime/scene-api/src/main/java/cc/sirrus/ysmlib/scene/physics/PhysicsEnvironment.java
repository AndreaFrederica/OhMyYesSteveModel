package cc.sirrus.ysmlib.scene.physics;

import cc.sirrus.ysmlib.scene.*;
import java.util.Objects;

/** Fixed-frame inertial forces in an accelerating/rotating host frame, plus fluid and solid contact. */
public record PhysicsEnvironment(ScenePhysicsInput input,Vec3 velocity,Vec3 acceleration,
    Vec3 angularVelocity,Vec3 angularAcceleration,boolean reset) {
  public PhysicsEnvironment {Objects.requireNonNull(input);Objects.requireNonNull(velocity);Objects.requireNonNull(acceleration);
    Objects.requireNonNull(angularVelocity);Objects.requireNonNull(angularAcceleration);}
  public Vec3 sourceGravity(){return input.sourceToWorld().inverse().transformDirection(input.gravity());}
  public int solidCount(){return input.settings().worldCollision()?input.solids().size():0;}
  public int packedSize(){return 51+solidCount()*24+input.fluids().size()*10;}
  /** Shared ABI layout for native direct transfers and the JVM reactor; flips only the caller's view. */
  public void writePacked(java.nio.FloatBuffer b){
    if(b.remaining()<packedSize())throw new IllegalArgumentException("Insufficient environment buffer");
    var inverse=input.sourceToWorld().inverse();b.put(input.sourceToWorld().copy()).put(inverse.copy());
    put(b,velocity);put(b,acceleration);put(b,angularVelocity);put(b,angularAcceleration);put(b,input.gravity());
    b.put((float)input.settings().inertia()).put((float)input.settings().fluidDrag()).put((float)input.settings().buoyancy()).put(reset?1:0);
    if(input.settings().worldCollision())for(var box:input.solids())for(int i=0;i<8;i++)put(b,inverse.transformPoint(box.corner(i)));
    for(var fluid:input.fluids()){put(b,fluid.volume().min());put(b,fluid.volume().max());put(b,fluid.flow());b.put(fluid.density());}
  }
  private static void put(java.nio.FloatBuffer b,Vec3 v){b.put(v.x()).put(v.y()).put(v.z());}
  /** Evaluated in world metres first, then converted to source units. Axial vectors stay world-handed. */
  public Vec3 accelerationAt(Vec3 sourcePosition,Vec3 sourceVelocity) {
    return accelerationAt(sourcePosition,sourceVelocity,0);
  }
  public Vec3 accelerationAt(Vec3 sourcePosition,Vec3 sourceVelocity,double stepSeconds) {
    var transform=input.sourceToWorld();var inverse=transform.inverse();
    var r=transform.transformDirection(sourcePosition);var v=transform.transformDirection(sourceVelocity);
    var fictitious=acceleration.add(angularAcceleration.cross(r)).add(angularVelocity.cross(angularVelocity.cross(r)))
        .add(angularVelocity.cross(v).multiply(2)).multiply((float)-input.settings().inertia());
    var p=transform.transformPoint(sourcePosition);
    for(var fluid:input.fluids())if(fluid.volume().contains(p)) {
      var worldVelocity=v.add(velocity).add(angularVelocity.cross(r));
      float drag=(float)(input.settings().fluidDrag()/(1+input.settings().fluidDrag()*stepSeconds));
      fictitious=fictitious.add(fluid.flow().subtract(worldVelocity).multiply(drag))
          .subtract(input.gravity().multiply((float)input.settings().buoyancy()*fluid.density()));
      break;
    }
    return inverse.transformDirection(fictitious);
  }
  /** Project a SpringBone tip sphere against the host boxes in world units (VRM's point solver). */
  public Vec3 collideTip(Vec3 tip,float radius) {
    if(!input.settings().worldCollision())return tip;
    var matrix=input.sourceToWorld();var inverse=matrix.inverse();var p=matrix.transformPoint(tip);
    float scale=0;
    for(var axis:new Vec3[]{new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1)}) {
      var direction=matrix.transformDirection(axis);
      scale=Math.max(scale,(float)Math.sqrt(direction.dot(direction)));
    }
    float r=radius*scale;
    for(var box:input.solids()) {
      var min=box.min();var max=box.max();
      var nearest=new Vec3(Math.max(min.x(),Math.min(max.x(),p.x())),Math.max(min.y(),Math.min(max.y(),p.y())),Math.max(min.z(),Math.min(max.z(),p.z())));
      var delta=p.subtract(nearest);double distance=Math.sqrt(delta.dot(delta));
      if(distance>1e-10&&distance<r)p=nearest.add(delta.multiply((float)(r/distance)));
      else if(distance<=1e-10&&box.contains(p)) {
        float[] distances={p.x()-min.x(),max.x()-p.x(),p.y()-min.y(),max.y()-p.y(),p.z()-min.z(),max.z()-p.z()};
        int face=0;for(int i=1;i<6;i++)if(distances[i]<distances[face])face=i;
        p=switch(face){case 0->new Vec3(min.x()-r,p.y(),p.z());case 1->new Vec3(max.x()+r,p.y(),p.z());
          case 2->new Vec3(p.x(),min.y()-r,p.z());case 3->new Vec3(p.x(),max.y()+r,p.z());
          case 4->new Vec3(p.x(),p.y(),min.z()-r);default->new Vec3(p.x(),p.y(),max.z()+r);};
      }
    }
    return inverse.transformPoint(p);
  }
}
