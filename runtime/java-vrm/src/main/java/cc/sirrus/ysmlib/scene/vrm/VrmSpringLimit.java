package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmDocument.*;

/** VRMC_springBone_limit 1.0, including the specification's singular-direction choices. */
final class VrmSpringLimit {
  private final Limit limit;
  private final Rotation local;
  private final double angle,yaw,cos,sin;
  VrmSpringLimit(Limit limit,Vec3 boneAxis,Rotation rest) {
    this.limit=limit;var axis=boneAxis.normalized();
    var align=axis.y()<=-1+1e-7?Rotation.normalized(1,0,0,0):Rotation.normalized(axis.z(),0,-axis.x(),axis.y()+1);
    local=rest.multiply(align).multiply(limit.rotation());angle=Math.min(Math.PI,limit.angle());yaw=Math.min(Math.PI/2,limit.yaw());cos=Math.cos(angle);sin=Math.sin(angle);
  }
  Vec3 constrain(Vec3 head,Vec3 tail,float length,Rotation parent) {
    var world=parent.multiply(local);var delta=tail.subtract(head);var direction=delta.dot(delta)<1e-24?new Vec3(0,1,0):world.inverse().rotate(delta.normalized());
    return head.add(world.rotate(direction(direction)).multiply(length));
  }
  Vec3 direction(Vec3 d) {
    double x=d.x(),y=d.y(),z=d.z();
    if(limit.type()==LimitType.CONE){if(y<cos){double h=Math.hypot(x,z);if(h<1e-7){x=0;z=sin;}else{x*=sin/h;z*=sin/h;}y=cos;}}
    else if(limit.type()==LimitType.HINGE){double h=Math.hypot(y,z);if(h<1e-7){x=0;y=1;z=0;}else{x=0;y/=h;z/=h;if(y<cos){y=cos;z=z<0?-sin:sin;}}}
    else {double pitch=Math.hypot(y,z)<1e-7?0:(y<0&&Math.abs(z)<1e-7?Math.PI:Math.atan2(z,y));double a=Math.asin(Math.max(-1,Math.min(1,x)));pitch=Math.max(-angle,Math.min(angle,pitch));a=Math.max(-yaw,Math.min(yaw,a));x=Math.sin(a);y=Math.cos(a)*Math.cos(pitch);z=Math.cos(a)*Math.sin(pitch);}
    return new Vec3((float)x,(float)y,(float)z);
  }
}
