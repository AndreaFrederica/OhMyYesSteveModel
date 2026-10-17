package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Compiled glTF LBS path: no per-vertex matrices/vectors, no discarded immutable-array copies. */
public final class CompiledSceneDeformer {
  private final MeshAsset.Primitive mesh;
  private final int[] offsets,joints;
  private final float[] weights;
  private final boolean linear;
  private List<Matrix4> previousPalette;
  private FloatData previousWeights;
  private Map<String,MeshAsset.Attribute> previous;
  public CompiledSceneDeformer(MeshAsset.Primitive mesh) {
    this.mesh=Objects.requireNonNull(mesh);var skin=mesh.skinning();linear=skin!=null&&skin.deforms().stream().allMatch(d->d==MeshAsset.Deform.LINEAR);
    offsets=linear?skin.offsets().copy():null;joints=linear?skin.joints().copy():null;weights=linear?skin.weights().copy():null;
    if(linear)for(int v=0;v<mesh.vertexCount();v++){double sum=0;for(int k=offsets[v];k<offsets[v+1];k++)sum+=weights[k];if(sum<1e-12)throw new IllegalArgumentException("Unweighted skinned vertex");}
  }
  public Map<String,MeshAsset.Attribute> deform(List<Matrix4> palette,FloatData morphWeights) {
    if(previous!=null&&morphWeights.equals(previousWeights)&&palette.equals(previousPalette))return previous;
    Map<String,MeshAsset.Attribute> result;
    if(!linear)result=new Deformer().deform(mesh,palette,morphWeights,Deformer.NormalMode.INVERSE_TRANSPOSE);
    else {
      float[] matrices=new float[palette.size()*12];for(int j=0;j<palette.size();j++){var m=palette.get(j);if(!m.isAffine())throw new IllegalArgumentException("Skin palette must be affine");for(int c=0;c<4;c++)for(int r=0;r<3;r++)matrices[j*12+c*3+r]=m.get(c,r);}
      var output=Deformer.morph(mesh,morphWeights);var positions=output.get("POSITION");var normals=output.get("NORMAL");var tangents=output.get("TANGENT");float[] m=new float[12];
      for(int v=0;v<mesh.vertexCount();v++) {
        Arrays.fill(m,0);for(int k=offsets[v];k<offsets[v+1];k++)if(weights[k]!=0){int joint=joints[k];if(joint<0||joint>=palette.size())throw new IllegalArgumentException("Joint outside palette");int at=joint*12;for(int i=0;i<12;i++)m[i]+=weights[k]*matrices[at+i];}
        int p=v*3;float x=positions[p],y=positions[p+1],z=positions[p+2];positions[p]=m[0]*x+m[3]*y+m[6]*z+m[9];positions[p+1]=m[1]*x+m[4]*y+m[7]*z+m[10];positions[p+2]=m[2]*x+m[5]*y+m[8]*z+m[11];
        float cx=m[4]*m[8]-m[5]*m[7],cy=m[5]*m[6]-m[3]*m[8],cz=m[3]*m[7]-m[4]*m[6];
        float det=m[0]*cx+m[1]*cy+m[2]*cz;
        if(normals!=null){x=normals[p];y=normals[p+1];z=normals[p+2];float sign=det<0?-1:1;
          normals[p]=sign*(cx*x+(m[7]*m[2]-m[8]*m[1])*y+(m[1]*m[5]-m[2]*m[4])*z);
          normals[p+1]=sign*(cy*x+(m[8]*m[0]-m[6]*m[2])*y+(m[2]*m[3]-m[0]*m[5])*z);
          normals[p+2]=sign*(cz*x+(m[6]*m[1]-m[7]*m[0])*y+(m[0]*m[4]-m[1]*m[3])*z);unit(normals,p);
        }
        if(tangents!=null){int t=v*4;x=tangents[t];y=tangents[t+1];z=tangents[t+2];float tx=m[0]*x+m[3]*y+m[6]*z,ty=m[1]*x+m[4]*y+m[7]*z,tz=m[2]*x+m[5]*y+m[8]*z;
          if(normals!=null){float dot=normals[p]*tx+normals[p+1]*ty+normals[p+2]*tz;tx-=normals[p]*dot;ty-=normals[p+1]*dot;tz-=normals[p+2]*dot;}
          tangents[t]=tx;tangents[t+1]=ty;tangents[t+2]=tz;unit(tangents,t);if(det<0)tangents[t+3]*=-1;
        }
      }
      var frozen=new LinkedHashMap<>(mesh.attributes());output.forEach((name,data)->frozen.put(name,new MeshAsset.Attribute(mesh.attributes().get(name).components(),FloatData.owned(data))));result=Map.copyOf(frozen);
    }
    previousPalette=List.copyOf(palette);previousWeights=morphWeights;previous=result;return result;
  }
  private static void unit(float[] values,int at){double x=values[at],y=values[at+1],z=values[at+2],length=Math.sqrt(x*x+y*y+z*z);float scale=length<1e-30?0:(float)(1/length);values[at]*=scale;values[at+1]*=scale;values[at+2]*=scale;}
}
