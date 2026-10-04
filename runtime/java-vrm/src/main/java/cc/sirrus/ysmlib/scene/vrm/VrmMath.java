package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;

final class VrmMath {
  private VrmMath() {}
  static Vec3 position(Matrix4 m) { return new Vec3(m.get(3,0),m.get(3,1),m.get(3,2)); }
  static double determinant(Matrix4 m) { return column(m,0).dot(column(m,1).cross(column(m,2))); }
  static Vec3 column(Matrix4 m,int c) { return new Vec3(m.get(c,0),m.get(c,1),m.get(c,2)); }
  static Rotation restRotation(SceneAsset.Node node) { return node.matrix()==null?node.transform().rotation():matrixRotation(node.matrix()); }
  /** glTF matrix nodes must be decomposable TRS. Reflection is assigned to X; the original matrix stays intact. */
  static Rotation matrixRotation(Matrix4 m) {
    if(!m.isAffine() || determinant(m)==0) throw new IllegalArgumentException("Constraint matrix requires a nonsingular affine TRS node");
    Vec3 x=column(m,0).normalized(),y=column(m,1).normalized(),z=column(m,2).normalized();
    if(determinant(m)<0) x=x.multiply(-1);
    if(Math.abs(x.dot(y))>1e-5 || Math.abs(x.dot(z))>1e-5 || Math.abs(y.dot(z))>1e-5) throw new IllegalArgumentException("Sheared local matrix has no lossless TRS rotation");
    double m00=x.x(),m01=y.x(),m02=z.x(),m10=x.y(),m11=y.y(),m12=z.y(),m20=x.z(),m21=y.z(),m22=z.z();
    double qx,qy,qz,qw,trace=m00+m11+m22;
    if(trace>0) { double s=2*Math.sqrt(trace+1);qw=s/4;qx=(m21-m12)/s;qy=(m02-m20)/s;qz=(m10-m01)/s; }
    else if(m00>m11 && m00>m22) { double s=2*Math.sqrt(1+m00-m11-m22);qw=(m21-m12)/s;qx=s/4;qy=(m01+m10)/s;qz=(m02+m20)/s; }
    else if(m11>m22) { double s=2*Math.sqrt(1+m11-m00-m22);qw=(m02-m20)/s;qx=(m01+m10)/s;qy=s/4;qz=(m12+m21)/s; }
    else { double s=2*Math.sqrt(1+m22-m00-m11);qw=(m10-m01)/s;qx=(m02+m20)/s;qy=(m12+m21)/s;qz=s/4; }
    return Rotation.normalized((float)qx,(float)qy,(float)qz,(float)qw);
  }
  static Rotation fromTo(Vec3 from,Vec3 to) {
    if(from.dot(from)<1e-24 || to.dot(to)<1e-24) return Rotation.IDENTITY;
    Vec3 a=from.normalized(),b=to.normalized();double dot=Math.max(-1,Math.min(1,a.dot(b)));
    if(dot< -1+1e-7) {
      Vec3 cross=Math.abs(a.x())>Math.abs(a.z())?new Vec3(-a.y(),a.x(),0):new Vec3(0,-a.z(),a.y());
      return Rotation.axisAngle(cross,Math.PI);
    }
    Vec3 cross=a.cross(b);return Rotation.normalized(cross.x(),cross.y(),cross.z(),(float)(1+dot));
  }
  /** Replace rotation while retaining every original linear and translation component. */
  static Matrix4 rotate(Matrix4 local,Rotation previous,Rotation next) {
    Rotation delta=next.multiply(previous.inverse());float[] result=local.copy();
    for(int c=0;c<3;c++) { Vec3 v=delta.rotate(column(local,c));result[c*4]=v.x();result[c*4+1]=v.y();result[c*4+2]=v.z(); }
    return new Matrix4(result);
  }
}
