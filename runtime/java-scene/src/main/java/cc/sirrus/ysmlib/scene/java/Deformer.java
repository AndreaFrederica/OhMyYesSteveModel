package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Reference CPU deformation. No fixed influence or morph limits; source mesh data is never modified. */
public final class Deformer {
  public enum NormalMode { INVERSE_TRANSPOSE, MMD_WEIGHTED_ROTATION }
  public Map<String,MeshAsset.Attribute> deform(MeshAsset.Primitive mesh,List<Matrix4> palette,FloatData morphWeights,NormalMode normalMode) {
    Objects.requireNonNull(normalMode);
    var output=morph(mesh,morphWeights);
    var skin=mesh.skinning();
    if(skin!=null) {
      skin(mesh,palette,normalMode,output);
    } else {
      for(int v=0;v<mesh.vertexCount();v++) {
        if(output.containsKey("NORMAL")) put(output.get("NORMAL"),v*3,unit(vec(output.get("NORMAL"),v*3)));
        if(output.containsKey("TANGENT")) {
          var t=vec(output.get("TANGENT"),v*4);
          if(output.containsKey("NORMAL")) { var n=vec(output.get("NORMAL"),v*3);t=t.subtract(n.multiply(dot(n,t))); }
          put(output.get("TANGENT"),v*4,unit(t));
        }
      }
    }
    return freeze(mesh,output);
  }
  /** Fresh owned arrays after ordered sparse morph accumulation, before skinning. */
  public static Map<String,float[]> morph(MeshAsset.Primitive mesh,FloatData morphWeights) {
    var output=new LinkedHashMap<String,float[]>();morphInto(mesh,morphWeights,output);return output;
  }
  /** Resets all previously used arrays before applying this frame, preserving source immutability. */
  public static void morphInto(MeshAsset.Primitive mesh,FloatData morphWeights,Map<String,float[]> output) {
    if(morphWeights.size()!=mesh.morphs().size()) throw new IllegalArgumentException("Morph weight count mismatch");
    // Immutable static attributes (UV, color, edge scale) can be shared with the source.
    for(var name:List.of("POSITION","NORMAL","TANGENT")) {var attribute=mesh.attributes().get(name);if(attribute!=null)output.computeIfAbsent(name,ignored->new float[attribute.values().size()]);}
    output.forEach((name,values)->{var base=mesh.attributes().get(name);if(base==null || values.length!=base.values().size())throw new IllegalArgumentException("Morph scratch layout mismatch");base.values().copyTo(values,0);});
    for(int target=0;target<mesh.morphs().size();target++) {
      float weight=morphWeights.get(target);
      var morph=mesh.morphs().get(target);
      for(var entry:morph.attributes().entrySet()) {
        String name=entry.getKey();var delta=entry.getValue();var base=mesh.attributes().get(name);
        if(base==null) throw new IllegalArgumentException("Morph attribute has no base: "+name);
        int components=base.components();
        if(delta.components()!=components && !(name.equals("TANGENT") && delta.components()==3 && components==4))
          throw new IllegalArgumentException("Morph attribute shape mismatch: "+name);
        if(weight==0) continue;
        float[] result=output.computeIfAbsent(name,ignored->base.values().copy());
        for(int row=0;row<delta.count();row++) {
          int vertex=morph.sparse()?morph.vertexIndices().get(row):row;
          for(int k=0;k<delta.components();k++) result[vertex*components+k]+=weight*delta.values().get(row*delta.components()+k);
        }
      }
    }
  }
  private static void skin(MeshAsset.Primitive mesh,List<Matrix4> palette,NormalMode normalMode,Map<String,float[]> output) {
      var skin=mesh.skinning();
      for(var matrix:palette) if(!matrix.isAffine()) throw new IllegalArgumentException("Skin palette must be affine");
      for(int v=0;v<mesh.vertexCount();v++) {
        int begin=skin.offsets().get(v),end=skin.offsets().get(v+1);double sum=0;
        for(int i=begin;i<end;i++) {
          if(skin.weights().get(i)!=0 && (skin.joints().get(i)<0 || skin.joints().get(i)>=palette.size()))
            throw new IllegalArgumentException("Joint outside palette");
          sum+=skin.weights().get(i);
        }
        if(sum<1e-12) throw new IllegalArgumentException("Unweighted skinned vertex");
        Matrix4 matrix;
        if(skin.deforms().get(v)==MeshAsset.Deform.SDEF) {
          if(Math.abs(sum-1)>0.0001) throw new IllegalArgumentException("SDEF weights must sum to one");
          int first=skin.joints().get(begin),second=skin.joints().get(begin+1);
          // An unused -1 bone has no contribution, including at an SDEF endpoint.
          var m0=first<0?Matrix4.IDENTITY:palette.get(first);var m1=second<0?Matrix4.IDENTITY:palette.get(second);
          double w0=skin.weights().get(begin),w1=skin.weights().get(begin+1);
          var q=slerp(rotation(m0),rotation(m1),w1);
          var c=vec(skin.sdef(),v*9);var r0=vec(skin.sdef(),v*9+3);var r1=vec(skin.sdef(),v*9+6);
          var rw=r0.multiply((float)w0).add(r1.multiply((float)w1));
          var cr0=c.add(r0.subtract(rw).multiply(.5f));var cr1=c.add(r1.subtract(rw).multiply(.5f));
          var position=rotate(q,vec(output.get("POSITION"),v*3).subtract(c))
              .add(m0.transformPoint(cr0).multiply((float)w0)).add(m1.transformPoint(cr1).multiply((float)w1));
          put(output.get("POSITION"),v*3,position);
          matrix=rotationMatrix(q,Vec3.ZERO);
        } else {
          matrix=skin.deforms().get(v)==MeshAsset.Deform.QDEF?dualQuaternion(skin,begin,end,palette):linear(skin,begin,end,palette);
          put(output.get("POSITION"),v*3,matrix.transformPoint(vec(output.get("POSITION"),v*3)));
        }
        if(output.containsKey("NORMAL")) {
          var normal=vec(output.get("NORMAL"),v*3);
          normal=normalMode==NormalMode.INVERSE_TRANSPOSE?normal(matrix,normal):unit(matrix.transformDirection(normal));
          put(output.get("NORMAL"),v*3,normal);
        }
        if(output.containsKey("TANGENT")) {
          float[] tangent=output.get("TANGENT");var t=matrix.transformDirection(vec(tangent,v*4));
          if(output.containsKey("NORMAL")) { var n=vec(output.get("NORMAL"),v*3);t=t.subtract(n.multiply(dot(n,t))); }
          put(tangent,v*4,unit(t));
          if(determinant(matrix)<0) tangent[v*4+3]*=-1;
        }
      }
  }
  /** Freezes by copying; retained frames cannot alias reusable scratch arrays. */
  public static Map<String,MeshAsset.Attribute> freeze(MeshAsset.Primitive mesh,Map<String,float[]> output) {
    var frozen=new LinkedHashMap<String,MeshAsset.Attribute>(mesh.attributes());
    output.forEach((name,values)->frozen.put(name,new MeshAsset.Attribute(mesh.attributes().get(name).components(),new FloatData(values))));
    return Collections.unmodifiableMap(frozen);
  }
  private static Matrix4 linear(MeshAsset.Skinning skin,int begin,int end,List<Matrix4> palette) {
    float[] m=new float[16];
    for(int i=begin;i<end;i++) {
      float weight=skin.weights().get(i);if(weight==0) continue;var p=palette.get(skin.joints().get(i));
      for(int c=0;c<4;c++) for(int r=0;r<3;r++) m[c*4+r]+=weight*p.get(c,r);
    }
    m[15]=1;return new Matrix4(m);
  }
  private static Matrix4 dualQuaternion(MeshAsset.Skinning skin,int begin,int end,List<Matrix4> palette) {
    double[] real=new double[4],dual=new double[4],reference=null;
    for(int i=begin;i<end;i++) {
      double weight=skin.weights().get(i);if(weight==0) continue;var matrix=palette.get(skin.joints().get(i));
      double[] q=rotation(matrix),d=multiply(new double[]{matrix.get(3,0),matrix.get(3,1),matrix.get(3,2),0},q);
      if(reference==null) reference=q;
      if(dot(reference,q)<0) weight=-weight;
      for(int k=0;k<4;k++) { real[k]+=weight*q[k];dual[k]+=.5*weight*d[k]; }
    }
    double length=Math.sqrt(dot(real,real));if(length<1e-12) throw new IllegalArgumentException("Degenerate dual quaternion blend");
    for(int k=0;k<4;k++) { real[k]/=length;dual[k]/=length; }
    // Orthogonalize the dual part so both dual-quaternion unit constraints hold.
    double projection=dot(real,dual);for(int k=0;k<4;k++) dual[k]-=real[k]*projection;
    var translation=multiply(dual,new double[]{-real[0],-real[1],-real[2],real[3]});
    return rotationMatrix(real,new Vec3((float)(2*translation[0]),(float)(2*translation[1]),(float)(2*translation[2])));
  }
  private static Matrix4 rotationMatrix(double[] q,Vec3 t) {
    return new Transform(t,Rotation.normalized((float)q[0],(float)q[1],(float)q[2],(float)q[3]),Vec3.ONE).matrix();
  }
  private static double[] rotation(Matrix4 m) {
    for(int c=0;c<3;c++) for(int d=0;d<3;d++) {
      double product=0;for(int r=0;r<3;r++) product+=(double)m.get(c,r)*m.get(d,r);
      if(Math.abs(product-(c==d?1:0))>.002) throw new IllegalArgumentException("SDEF/QDEF require rigid bone transforms");
    }
    if(determinant(m)<0) throw new IllegalArgumentException("SDEF/QDEF cannot use reflected bone transforms");
    double[] q=new double[4];double trace=m.get(0,0)+m.get(1,1)+m.get(2,2);
    if(trace>0) {
      double s=Math.sqrt(trace+1)*2;q[3]=s/4;q[0]=(m.get(1,2)-m.get(2,1))/s;
      q[1]=(m.get(2,0)-m.get(0,2))/s;q[2]=(m.get(0,1)-m.get(1,0))/s;
    } else {
      int a=m.get(0,0)>m.get(1,1)?0:1;if(m.get(2,2)>m.get(a,a)) a=2;
      int b=(a+1)%3,c=(a+2)%3;double s=Math.sqrt(1+m.get(a,a)-m.get(b,b)-m.get(c,c))*2;
      q[a]=s/4;q[b]=(m.get(a,b)+m.get(b,a))/s;q[c]=(m.get(a,c)+m.get(c,a))/s;q[3]=(m.get(b,c)-m.get(c,b))/s;
    }
    double length=Math.sqrt(dot(q,q));for(int k=0;k<4;k++) q[k]/=length;return q;
  }
  private static double[] slerp(double[] a,double[] b,double t) {
    double d=dot(a,b);if(d<0) { for(int k=0;k<4;k++) b[k]=-b[k];d=-d; }
    double x=1-t,y=t;if(d<.9995) { double angle=Math.acos(Math.min(1,d)),sin=Math.sin(angle);x=Math.sin((1-t)*angle)/sin;y=Math.sin(t*angle)/sin; }
    double[] q=new double[4];for(int k=0;k<4;k++) q[k]=x*a[k]+y*b[k];
    double n=Math.sqrt(dot(q,q));for(int k=0;k<4;k++) q[k]/=n;return q;
  }
  private static double[] multiply(double[] a,double[] b) {
    return new double[]{a[3]*b[0]+a[0]*b[3]+a[1]*b[2]-a[2]*b[1],a[3]*b[1]-a[0]*b[2]+a[1]*b[3]+a[2]*b[0],
        a[3]*b[2]+a[0]*b[1]-a[1]*b[0]+a[2]*b[3],a[3]*b[3]-a[0]*b[0]-a[1]*b[1]-a[2]*b[2]};
  }
  private static Vec3 rotate(double[] q,Vec3 p) { return rotationMatrix(q,Vec3.ZERO).transformDirection(p); }
  private static Vec3 vec(FloatData data,int i) { return new Vec3(data.get(i),data.get(i+1),data.get(i+2)); }
  private static Vec3 vec(float[] data,int i) { return new Vec3(data[i],data[i+1],data[i+2]); }
  private static void put(float[] out,int i,Vec3 v) { out[i]=v.x();out[i+1]=v.y();out[i+2]=v.z(); }
  private static float dot(Vec3 a,Vec3 b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
  private static double dot(double[] a,double[] b) { double sum=0;for(int i=0;i<a.length;i++) sum+=a[i]*b[i];return sum; }
  private static Vec3 cross(Vec3 a,Vec3 b) { return new Vec3(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x()); }
  private static Vec3 column(Matrix4 m,int c) { return new Vec3(m.get(c,0),m.get(c,1),m.get(c,2)); }
  private static float determinant(Matrix4 m) { return dot(column(m,0),cross(column(m,1),column(m,2))); }
  private static Vec3 unit(Vec3 v) { double n=Math.sqrt((double)v.x()*v.x()+(double)v.y()*v.y()+(double)v.z()*v.z());return n<1e-30?Vec3.ZERO:v.multiply((float)(1/n)); }
  private static Vec3 normal(Matrix4 m,Vec3 n) {
    var a=column(m,0);var b=column(m,1);var c=column(m,2);
    var transformed=cross(b,c).multiply(n.x()).add(cross(c,a).multiply(n.y())).add(cross(a,b).multiply(n.z()));
    if(determinant(m)<0) transformed=transformed.multiply(-1);
    return unit(transformed);
  }
}
