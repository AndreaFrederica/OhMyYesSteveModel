package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.SceneEvaluator;
import java.util.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmDocument.*;

final class VrmGaze {
  record Output(ScenePose pose,List<Rotation> rotations,Map<String,Float> expressions,VrmEvaluation.Angles angles) {}
  private final VrmDocument document;
  private final int[] parents,order;
  private final Rotation[] restLocal,restWorld;
  private final Rotation face;
  VrmGaze(VrmDocument document) {
    this.document=document;parents=VrmTopology.parents(document.scene());order=hierarchyOrder(document.scene(),parents);restLocal=new Rotation[parents.length];restWorld=new Rotation[parents.length];
    for(int n:order) { restLocal[n]=VrmMath.restRotation(document.scene().nodes().get(n));restWorld[n]=parents[n]==-1?restLocal[n]:restWorld[parents[n]].multiply(restLocal[n]); }
    face=document.version()==Version.VRM_0?Rotation.axisAngle(new Vec3(0,1,0),Math.PI):Rotation.IDENTITY;
  }
  Output evaluate(ScenePose pose,List<Rotation> rotations,VrmEvaluation.Gaze input) {
    var settings=document.lookAt();if(settings==null || input==null) return new Output(pose,rotations,Map.of(),null);
    VrmEvaluation.Angles angles;
    if(input instanceof VrmEvaluation.Angles supplied) angles=supplied;
    else {
      int head=document.humanBones().get("head");Vec3 offset=settings.offset();if(document.version()==Version.VRM_0) offset=new Vec3(offset.x(),offset.y(),-offset.z());
      Matrix4 lookSpace=pose.globalMatrices().get(head).multiply(new Transform(offset,restWorld[head].inverse().multiply(face),Vec3.ONE).matrix());
      Vec3 direction=lookSpace.inverse().transformPoint(((VrmEvaluation.Target)input).position());
      angles=new VrmEvaluation.Angles(Math.toDegrees(Math.atan2(direction.x(),direction.z())),Math.toDegrees(Math.atan2(-direction.y(),Math.hypot(direction.x(),direction.z()))));
    }
    double yaw=angles.yaw(),pitch=angles.pitch();
    if(settings.type().equals("expression")) {
      var result=new LinkedHashMap<String,Float>();boolean legacy=document.version()==Version.VRM_0;
      result.put(legacy?"lookleft":"lookLeft",yaw>0?map(settings.horizontalOuter(),yaw):0f);result.put(legacy?"lookright":"lookRight",yaw<0?map(settings.horizontalOuter(),-yaw):0f);
      result.put(legacy?"lookdown":"lookDown",pitch>0?map(settings.verticalDown(),pitch):0f);result.put(legacy?"lookup":"lookUp",pitch<0?map(settings.verticalUp(),-pitch):0f);
      var declared=new HashSet<String>();for(var e:document.expressions()) declared.add(e.key());result.keySet().retainAll(declared);
      return new Output(pose,rotations,result,angles);
    }
    Matrix4[] local=pose.localMatrices().toArray(Matrix4[]::new),global=new Matrix4[parents.length];Rotation[] result=rotations.toArray(Rotation[]::new);
    for(String eye:List.of("leftEye","rightEye")) {
      Integer n=document.humanBones().get(eye);if(n==null) continue;boolean outer=eye.equals("leftEye")== (yaw>=0);
      double y=Math.copySign(map(outer?settings.horizontalOuter():settings.horizontalInner(),Math.abs(yaw)),yaw);
      double x=Math.copySign(map(pitch>=0?settings.verticalDown():settings.verticalUp(),Math.abs(pitch)),pitch);
      Rotation delta=Rotation.axisAngle(new Vec3(0,1,0),Math.toRadians(y)).multiply(Rotation.axisAngle(new Vec3(1,0,0),Math.toRadians(x)));
      delta=face.multiply(delta).multiply(face.inverse());Rotation parent=parents[n]==-1?Rotation.IDENTITY:restWorld[parents[n]];
      result[n]=parent.inverse().multiply(delta).multiply(parent).multiply(restLocal[n]);local[n]=VrmMath.rotate(local[n],rotations.get(n),result[n]);
    }
    for(int n:order) global[n]=parents[n]==-1?local[n]:global[parents[n]].multiply(local[n]);
    return new Output(new ScenePose(pose.seconds(),Arrays.asList(local),Arrays.asList(global),pose.morphWeights(),pose.extensionChannels()),Arrays.asList(result),Map.of(),angles);
  }
  static float map(RangeMap range,double value) {
    if(!Double.isFinite(value) || value<0) throw new IllegalArgumentException("Range map expects a nonnegative angle");
    // VRM 0 Unity CurveMapper clamps the denominator; VRM 1 explicitly defines the zero input-range step.
    double t=range.curve().size()==0?(range.inputMax()==0?(value==0?0:1):Math.min(1,value/range.inputMax())):Math.min(1,value/Math.max(range.inputMax(),.001));
    var curve=range.curve();if(curve.size()==0) return (float)(t*range.outputScale());
    if(t<=curve.get(0)) return curve.get(1)*range.outputScale();if(t>=curve.get(curve.size()-4)) return curve.get(curve.size()-3)*range.outputScale();
    int i=0;while(i+4<curve.size() && curve.get(i+4)<=t) i+=4;
    double dt=curve.get(i+4)-curve.get(i),u=(t-curve.get(i))/dt,u2=u*u,u3=u2*u;
    return (float)(((2*u3-3*u2+1)*curve.get(i+1)+(u3-2*u2+u)*dt*curve.get(i+3)+(-2*u3+3*u2)*curve.get(i+5)+(u3-u2)*dt*curve.get(i+6))*range.outputScale());
  }
  static int[] hierarchyOrder(SceneAsset scene,int[] parents) {
    var queue=new ArrayDeque<Integer>();for(int n=0;n<parents.length;n++) if(parents[n]==-1) queue.add(n);int[] order=new int[parents.length];int i=0;
    while(!queue.isEmpty()) { int n=queue.remove();order[i++]=n;for(int c:scene.nodes().get(n).children().copy()) queue.add(c); }return order;
  }
}
