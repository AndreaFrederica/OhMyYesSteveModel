package cc.sirrus.ysmlib.scene;

import java.util.*;

/** Immutable node topology and rest transforms. Matrix nodes keep their original scale and reflection. */
public final class SceneRig {
  private final SceneAsset scene;
  private final int[] parents,order;
  private final List<Matrix4> restGlobal;
  private volatile List<Rotation> restRotations;
  public SceneRig(SceneAsset scene) {
    this.scene=Objects.requireNonNull(scene);int count=scene.nodes().size();parents=new int[count];Arrays.fill(parents,-1);
    for(int n=0;n<count;n++)for(int c:scene.nodes().get(n).children().copy()) {
      if(c<0||c>=count||parents[c]!=-1)throw new IllegalArgumentException("Invalid node parent");parents[c]=n;
    }
    order=new int[count];var queue=new ArrayDeque<Integer>();for(int n=0;n<count;n++)if(parents[n]<0)queue.add(n);
    int size=0;while(!queue.isEmpty()){int n=queue.remove();order[size++]=n;for(int c:scene.nodes().get(n).children().copy())queue.add(c);}
    if(size!=count)throw new IllegalArgumentException("Cyclic node hierarchy");
    restGlobal=global(scene.nodes().stream().map(SceneAsset.Node::localMatrix).toList());
  }
  public int parent(int node){return parents[node];}
  public List<Rotation> restRotations(){var result=restRotations;if(result==null){result=scene.nodes().stream().map(n->n.matrix()==null?n.transform().rotation():rotation(n.matrix())).toList();restRotations=result;}return result;}
  public List<Matrix4> restGlobal(){return restGlobal;}
  /** Convert rigid-retarget translations to actual parent coordinates, including animated parent scale. */
  public Map<Integer,Pose> scaledDeltas(Map<Integer,Pose> input) {
    if(input.isEmpty())return input;
    var matrices=new Matrix4[parents.length];var rotations=new Rotation[parents.length];var output=new LinkedHashMap<Integer,Pose>();
    var rest=restRotations();
    for(int n:order){int p=parents[n];var delta=input.getOrDefault(n,Pose.IDENTITY);var position=delta.position();
      if(p>=0&&input.containsKey(n))position=matrices[p].inverse().transformDirection(rotations[p].rotate(position));
      var rotation=rest.get(n).multiply(delta.rotation());var local=withRotation(scene.nodes().get(n).localMatrix(),rest.get(n),rotation);
      float[] values=local.copy();values[12]+=position.x();values[13]+=position.y();values[14]+=position.z();local=new Matrix4(values);
      matrices[n]=p<0?local:matrices[p].multiply(local);rotations[n]=p<0?rotation:rotations[p].multiply(rotation);
      if(input.containsKey(n))output.put(n,new Pose(position,delta.rotation()));
    }return Map.copyOf(output);
  }
  public List<Matrix4> global(List<Matrix4> local) {
    if(local.size()!=parents.length)throw new IllegalArgumentException("Node layout mismatch");
    var result=new Matrix4[parents.length];for(int n:order)result[n]=parents[n]<0?local.get(n):result[parents[n]].multiply(local.get(n));return List.of(result);
  }
  public static Vec3 position(Matrix4 m){return new Vec3(m.get(3,0),m.get(3,1),m.get(3,2));}
  public static Vec3 column(Matrix4 m,int c){return new Vec3(m.get(c,0),m.get(c,1),m.get(c,2));}
  public static Rotation rotation(Matrix4 m) {
    double det=column(m,0).dot(column(m,1).cross(column(m,2)));
    if(!m.isAffine()||det==0)throw new IllegalArgumentException("Node rotation requires nonsingular affine TRS");
    Vec3 x=column(m,0).normalized(),y=column(m,1).normalized(),z=column(m,2).normalized();if(det<0)x=x.multiply(-1);
    if(Math.abs(x.dot(y))>1e-5||Math.abs(x.dot(z))>1e-5||Math.abs(y.dot(z))>1e-5)throw new IllegalArgumentException("Sheared local matrix has no lossless rotation");
    double a=x.x(),b=y.x(),c=z.x(),d=x.y(),e=y.y(),f=z.y(),g=x.z(),h=y.z(),i=z.z(),qx,qy,qz,qw,t=a+e+i;
    if(t>0){double s=2*Math.sqrt(t+1);qw=s/4;qx=(h-f)/s;qy=(c-g)/s;qz=(d-b)/s;}
    else if(a>e&&a>i){double s=2*Math.sqrt(1+a-e-i);qw=(h-f)/s;qx=s/4;qy=(b+d)/s;qz=(c+g)/s;}
    else if(e>i){double s=2*Math.sqrt(1+e-a-i);qw=(c-g)/s;qx=(b+d)/s;qy=s/4;qz=(f+h)/s;}
    else{double s=2*Math.sqrt(1+i-a-e);qw=(d-b)/s;qx=(c+g)/s;qy=(f+h)/s;qz=s/4;}
    return Rotation.normalized((float)qx,(float)qy,(float)qz,(float)qw);
  }
  public static Matrix4 withRotation(Matrix4 matrix,Rotation previous,Rotation next) {
    var delta=next.multiply(previous.inverse());float[] values=matrix.copy();
    for(int c=0;c<3;c++){var v=delta.rotate(column(matrix,c));values[c*4]=v.x();values[c*4+1]=v.y();values[c*4+2]=v.z();}return new Matrix4(values);
  }
  /** Explicit host local deltas replace the bound node's source pose, preserving its rest scale. */
  public ScenePose deltas(ScenePose pose,Map<Integer,Pose> deltas) {
    if(deltas.isEmpty())return pose;var local=new ArrayList<>(pose.localMatrices());var restRotations=restRotations();
    for(var entry:deltas.entrySet()) {
      int n=entry.getKey();var node=scene.nodes().get(n);var delta=entry.getValue();
      var m=withRotation(node.localMatrix(),restRotations.get(n),restRotations.get(n).multiply(delta.rotation()));
      float[] values=m.copy();var p=position(m).add(delta.position());values[12]=p.x();values[13]=p.y();values[14]=p.z();local.set(n,new Matrix4(values));
    }
    return new ScenePose(pose.seconds(),local,global(local),pose.morphWeights(),pose.extensionChannels());
  }
}
