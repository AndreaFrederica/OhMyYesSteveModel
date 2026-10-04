package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;

/** CCD in the link parent's coordinates, with PMX Euler-limit ordering and early-iteration reflection. */
final class MmdIk {
  private static final Vec3 X=new Vec3(1,0,0),Y=new Vec3(0,1,0),Z=new Vec3(0,0,1);
  private final MmdRig rig;
  MmdIk(MmdRig rig) { this.rig=rig; }

  void solve(int controller,Ik ik) {
    if(ik.iterations()<0 || (long)ik.iterations()*ik.links().size()>4_000_000)
      throw new IllegalArgumentException("MMD IK evaluation budget exceeded");
    if(!Float.isFinite(ik.angleLimit()) || ik.angleLimit()<0) throw new IllegalArgumentException("Invalid IK angle limit");
    if(ik.iterations()==0 || ik.angleLimit()==0 || ik.links().isEmpty()) return;
    Vec3 goal=rig.world[controller].position();
    for(var link:ik.links()) if(!rig.overridden(link.bone())) rig.ikRotation[link.bone()]=Rotation.IDENTITY;
    for(int i=ik.links().size()-1;i>=0;i--) rig.updateSubtree(ik.links().get(i).bone());
    for(int iteration=0;iteration<ik.iterations();iteration++) {
      if(goal.subtract(rig.world[ik.target()].position()).dot(goal.subtract(rig.world[ik.target()].position()))<1e-10) break;
      for(int k=0;k<ik.links().size();k++) {
        var link=ik.links().get(k);int i=link.bone();if(i==ik.target() || rig.overridden(i)) continue;
        Vec3 origin=rig.world[i].position(),from=rig.world[ik.target()].position().subtract(origin),to=goal.subtract(origin);
        if(from.dot(from)<1e-16 || to.dot(to)<1e-16) continue;
        from=from.normalized();to=to.normalized();double cosine=Math.max(-1,Math.min(1,from.dot(to)));
        Vec3 axis=from.cross(to);
        // atan2 retains the small correction when float-normalized dot products round to 1.
        double angle=Math.min(ik.angleLimit()*(k+1d),Math.atan2(Math.sqrt(axis.dot(axis)),cosine));if(angle<1e-8) continue;
        if(axis.dot(axis)<1e-14) {
          if(cosine>=0) continue;
          Vec3 perpendicular=Math.abs(from.x())<Math.abs(from.y())?X:Y;
          if(Math.abs(from.z())<Math.min(Math.abs(from.x()),Math.abs(from.y()))) perpendicular=Z;
          axis=from.cross(perpendicular);
        }
        int parent=rig.source.bones().get(i).parent();
        if(parent>=0) axis=rig.world[parent].rotation().inverse().rotate(axis);
        axis=axis.normalized();Vec3 fixed=rig.source.bones().get(i).fixedAxis();
        if(fixed!=null && fixed.dot(fixed)>1e-15) axis=fixed.normalized().multiply(axis.dot(fixed)<0?-1:1);
        boolean reflect=iteration<ik.iterations()/2;
        if(link.minimum()!=null && reflect) {
          int single=singleAxis(link.minimum(),link.maximum());
          if(single==-2) continue;
          if(single>=0) { Vec3 unit=single==0?X:single==1?Y:Z;axis=unit.multiply(axis.dot(unit)<0?-1:1); }
        }
        var correction=Rotation.axisAngle(axis,angle).multiply(rig.ikRotation[i]);
        if(link.minimum()!=null) {
          var total=correction.multiply(rig.localRotation[i]);
          correction=constrain(total,link.minimum(),link.maximum(),reflect).multiply(rig.localRotation[i].inverse());
        }
        rig.ikRotation[i]=correction;rig.updateSubtree(i);
      }
    }
  }

  private static int singleAxis(Vec3 min,Vec3 max) {
    int count=0,axis=-2;float[] a={min.x(),min.y(),min.z()},b={max.x(),max.y(),max.z()};
    for(int i=0;i<3;i++) if(a[i]!=0 || b[i]!=0) { count++;axis=i; }return count>1?-1:axis;
  }

  static Rotation constrain(Rotation q,Vec3 a,Vec3 b,boolean reflect) {
    var min=new Vec3(Math.min(a.x(),b.x()),Math.min(a.y(),b.y()),Math.min(a.z(),b.z()));
    var max=new Vec3(Math.max(a.x(),b.x()),Math.max(a.y(),b.y()),Math.max(a.z(),b.z()));
    var m=new Pose(Vec3.ZERO,q).matrix();double x,y,z;int order;
    if(min.x()>-Math.PI/2 && max.x()<Math.PI/2) {
      order=0;x=asin(-m.get(2,1));y=Math.atan2(m.get(2,0),m.get(2,2));z=Math.atan2(m.get(0,1),m.get(1,1));
    } else if(min.y()>-Math.PI/2 && max.y()<Math.PI/2) {
      order=1;y=asin(-m.get(0,2));x=Math.atan2(m.get(1,2),m.get(2,2));z=Math.atan2(m.get(0,1),m.get(0,0));
    } else {
      order=2;z=asin(-m.get(1,0));x=Math.atan2(m.get(1,2),m.get(1,1));y=Math.atan2(m.get(2,0),m.get(0,0));
    }
    var rx=Rotation.axisAngle(X,limit(x,min.x(),max.x(),reflect));
    var ry=Rotation.axisAngle(Y,limit(y,min.y(),max.y(),reflect));
    var rz=Rotation.axisAngle(Z,limit(z,min.z(),max.z(),reflect));
    return switch(order) { case 0->ry.multiply(rx).multiply(rz);case 1->rz.multiply(ry).multiply(rx);default->rx.multiply(rz).multiply(ry); };
  }
  private static double asin(double value) { return Math.asin(Math.max(-1,Math.min(1,value))); }
  private static double limit(double value,double min,double max,boolean reflect) {
    if(value<min) { double reflected=2*min-value;return reflect && reflected<=max?reflected:min; }
    if(value>max) { double reflected=2*max-value;return reflect && reflected>=min?reflected:max; }return value;
  }
}
