package cc.sirrus.ysmlib.scene.physics;

import cc.sirrus.ysmlib.scene.*;

/** Host-frame derivatives are sampled once per logical frame, never once per draw/substep.
 * Uniform translation has zero inertial acceleration. Teleports/stalls establish a new baseline. */
public final class ScenePhysicsMotion {
  private ScenePhysicsInput previous;
  private Vec3 velocity=Vec3.ZERO,angular=Vec3.ZERO;
  private boolean hasVelocity;
  private PhysicsEnvironment last;
  public PhysicsEnvironment update(ScenePhysicsInput input) {
    if(previous!=null&&input.seconds()==previous.seconds())
      return new PhysicsEnvironment(input,velocity,last.acceleration(),angular,last.angularAcceleration(),false);
    Vec3 nextVelocity=Vec3.ZERO,nextAngular=Vec3.ZERO,acceleration=Vec3.ZERO,alpha=Vec3.ZERO;
    boolean reset=previous==null;double dt=previous==null?0:input.seconds()-previous.seconds();
    if(previous!=null) {
      var old=previous.sourceToWorld().transformPoint(Vec3.ZERO);var now=input.sourceToWorld().transformPoint(Vec3.ZERO);
      double x=input.originX()-previous.originX()+now.x()-old.x(),y=input.originY()-previous.originY()+now.y()-old.y(),z=input.originZ()-previous.originZ()+now.z()-old.z();
      reset=dt<=0||dt>.25||Math.hypot(Math.hypot(x,y),z)>input.settings().teleportDistance();
      if(!reset) {
        nextVelocity=limited(new Vec3((float)(x/dt),(float)(y/dt),(float)(z/dt)),100);
        // Corresponding basis axes have the same reflection and scale in both frames.
        var a=previous.sourceToWorld();var b=input.sourceToWorld();
        Vec3[] axes={new Vec3(1,0,0),new Vec3(0,1,0),new Vec3(0,0,1)};
        Vec3 sin=Vec3.ZERO;double cosine=0;
        for(var axis:axes){var u=a.transformDirection(axis).normalized();var v=b.transformDirection(axis).normalized();sin=sin.add(u.cross(v));cosine+=u.dot(v);}
        sin=sin.multiply(.5f);double length=Math.sqrt(sin.dot(sin)),angle=Math.atan2(length,Math.max(-1,Math.min(1,(cosine-1)/2)));
        for(var axis:axes)if(Math.abs(a.transformDirection(axis).dot(a.transformDirection(axis))-b.transformDirection(axis).dot(b.transformDirection(axis)))>
            Math.max(1e-20,a.transformDirection(axis).dot(a.transformDirection(axis)))*1e-4)reset=true;
        if(angle>Math.PI/2)reset=true;
        if(length>1e-7)nextAngular=limited(sin.multiply((float)(angle/length/dt)),20);
        if(hasVelocity){acceleration=limited(nextVelocity.subtract(velocity).multiply((float)(1/dt)),200);alpha=limited(nextAngular.subtract(angular).multiply((float)(1/dt)),100);}
      }
    }
    if(reset){nextVelocity=nextAngular=acceleration=alpha=Vec3.ZERO;}
    previous=input;velocity=nextVelocity;angular=nextAngular;hasVelocity=!reset;
    return last=new PhysicsEnvironment(input,nextVelocity,acceleration,nextAngular,alpha,reset);
  }
  private static Vec3 limited(Vec3 v,double maximum){double n=Math.sqrt(v.dot(v));return n>maximum?v.multiply((float)(maximum/n)):v;}
  public void reset(){previous=null;last=null;hasVelocity=false;velocity=angular=Vec3.ZERO;}
}
