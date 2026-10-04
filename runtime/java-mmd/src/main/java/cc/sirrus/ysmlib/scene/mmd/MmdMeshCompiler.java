package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Projects only mesh semantics. Bone/material/group/impulse morphs stay in the typed source for the MMD scheduler. */
public final class MmdMeshCompiler {
  public MeshAsset compile(PmxDocument source) {
    int n=source.vertices().size(),influences=0,uvCount=source.globals().unsigned(1);boolean sdef=false;
    for(var v:source.vertices()) { influences=Math.addExact(influences,v.bones().size());sdef|=v.deform()==3; }
    var positions=new float[n*3];var normals=new float[n*3];var edge=new float[n];var uv=new float[uvCount+1][n*4];
    var offsets=new int[n+1];var joints=new int[influences];var weights=new float[influences];var sdefData=new float[sdef?n*9:0];var deforms=new ArrayList<MeshAsset.Deform>(n);
    int cursor=0;
    for(int i=0;i<n;i++) {
      var v=source.vertices().get(i);put(positions,i*3,v.position());put(normals,i*3,v.normal());edge[i]=v.edgeScale();
      uv[0][i*4]=v.uv().get(0);uv[0][i*4+1]=v.uv().get(1);
      for(int channel=1;channel<=uvCount;channel++) for(int k=0;k<4;k++) uv[channel][i*4+k]=v.additionalUv().get((channel-1)*4+k);
      offsets[i]=cursor;for(int k=0;k<v.bones().size();k++) { joints[cursor]=v.bones().get(k);weights[cursor++]=v.weights().get(k); }
      deforms.add(switch(v.deform()) { case 3->MeshAsset.Deform.SDEF;case 4->MeshAsset.Deform.QDEF;default->MeshAsset.Deform.LINEAR; });
      if(v.deform()==3) { put(sdefData,i*9,v.sdefC());put(sdefData,i*9+3,v.sdefR0());put(sdefData,i*9+6,v.sdefR1()); }
    }
    offsets[n]=cursor;
    var attributes=attributes(positions,normals,edge,uv);var morphs=new ArrayList<MeshAsset.MorphTarget>();
    for(var morph:source.morphs()) {
      var data=new LinkedHashMap<String,MeshAsset.Attribute>();int count=morph.offsets().size();int[] vertexIndices=new int[0];
      if(morph.type()==1 && count>0) {
        vertexIndices=new int[count];float[] delta=new float[count*3];
        for(int i=0;i<count;i++) { var v=(PmxDocument.VertexOffset)morph.offsets().get(i);vertexIndices[i]=v.vertex();put(delta,i*3,v.translation()); }
        data.put("POSITION",new MeshAsset.Attribute(3,new FloatData(delta)));
      } else if(morph.type()>=3 && morph.type()<=7 && count>0) {
        int channel=morph.type()-3;if(channel>uvCount) throw new IllegalArgumentException("UV morph targets an undeclared additional UV channel");
        vertexIndices=new int[count];float[] full=new float[count*4],xy=new float[count*2];
        for(int i=0;i<count;i++) { var v=(PmxDocument.UvOffset)morph.offsets().get(i);vertexIndices[i]=v.vertex();
          for(int k=0;k<4;k++) full[i*4+k]=v.offset().get(k);xy[i*2]=v.offset().get(0);xy[i*2+1]=v.offset().get(1); }
        data.put("_MMD_UV"+channel,new MeshAsset.Attribute(4,new FloatData(full)));data.put("TEXCOORD_"+channel,new MeshAsset.Attribute(2,new FloatData(xy)));
      }
      morphs.add(new MeshAsset.MorphTarget(morph.names().local(),data,new IntData(vertexIndices)));
    }
    var skin=new MeshAsset.Skinning(new IntData(offsets),new IntData(joints),new FloatData(weights),deforms,new FloatData(sdefData));
    var primitives=new ArrayList<MeshAsset.Primitive>();int start=0;
    for(int i=0;i<source.materials().size();i++) {
      var material=source.materials().get(i);int count=material.indexCount();int[] indices=new int[count];for(int k=0;k<count;k++) indices[k]=source.indices().get(start+k);start+=count;
      // Preserve source faces. The material's point/line flags belong to the later MMD drawing passes.
      primitives.add(new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,attributes,new IntData(indices),i,skin,morphs));
    }
    return new MeshAsset(source.names().local(),primitives,new FloatData(new float[morphs.size()]));
  }
  public MeshAsset compile(PmdDocument source) {
    int n=source.vertices().size();float[] positions=new float[n*3],normals=new float[n*3],edge=new float[n];var uv=new float[1][n*4];
    int[] offsets=new int[n+1],joints=new int[n*2];float[] weights=new float[n*2];
    for(int i=0;i<n;i++) {
      var v=source.vertices().get(i);put(positions,i*3,v.position());put(normals,i*3,v.normal());edge[i]=v.edgeFlag()==0?1:0;
      uv[0][i*4]=v.uv().get(0);uv[0][i*4+1]=v.uv().get(1);offsets[i]=i*2;
      joints[i*2]=v.bone0()==65535?-1:v.bone0();joints[i*2+1]=v.bone1()==65535?-1:v.bone1();weights[i*2]=v.weightPercent()/100f;weights[i*2+1]=1-weights[i*2];
    }
    if(!source.morphs().isEmpty()) for(var base:source.morphs().get(0).vertices()) put(positions,base.index()*3,base.position());
    offsets[n]=n*2;var attributes=attributes(positions,normals,edge,uv);var morphs=new ArrayList<MeshAsset.MorphTarget>();
    for(int i=0;i<source.morphs().size();i++) {
      var morph=source.morphs().get(i);int count=morph.vertices().size();
      if(i==0 || count==0) { morphs.add(new MeshAsset.MorphTarget(morph.name(),Map.of()));continue; }
      int[] indices=new int[count];float[] delta=new float[count*3];
      for(int k=0;k<count;k++) { var v=morph.vertices().get(k);indices[k]=source.morphs().get(0).vertices().get(v.index()).index();put(delta,k*3,v.position()); }
      morphs.add(new MeshAsset.MorphTarget(morph.name(),Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(delta))),new IntData(indices)));
    }
    var skin=new MeshAsset.Skinning(new IntData(offsets),new IntData(joints),new FloatData(weights),Collections.nCopies(n,MeshAsset.Deform.LINEAR),FloatData.EMPTY);
    var primitives=new ArrayList<MeshAsset.Primitive>();int start=0;
    for(int i=0;i<source.materials().size();i++) { int count=source.materials().get(i).indexCount();int[] indices=new int[count];for(int k=0;k<count;k++) indices[k]=source.indices().get(start+k);start+=count;
      primitives.add(new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,attributes,new IntData(indices),i,skin,morphs)); }
    return new MeshAsset(source.name(),primitives,new FloatData(new float[morphs.size()]));
  }
  private static Map<String,MeshAsset.Attribute> attributes(float[] positions,float[] normals,float[] edge,float[][] uv) {
    var attributes=new LinkedHashMap<String,MeshAsset.Attribute>();attributes.put("POSITION",new MeshAsset.Attribute(3,new FloatData(positions)));
    attributes.put("NORMAL",new MeshAsset.Attribute(3,new FloatData(normals)));attributes.put("_MMD_EDGE_SCALE",new MeshAsset.Attribute(1,new FloatData(edge)));
    for(int channel=0;channel<uv.length;channel++) {
      attributes.put("_MMD_UV"+channel,new MeshAsset.Attribute(4,new FloatData(uv[channel])));float[] xy=new float[uv[channel].length/2];
      for(int i=0;i<xy.length/2;i++) { xy[i*2]=uv[channel][i*4];xy[i*2+1]=uv[channel][i*4+1]; }
      attributes.put("TEXCOORD_"+channel,new MeshAsset.Attribute(2,new FloatData(xy)));
    }
    return attributes;
  }
  private static void put(float[] out,int at,Vec3 value) { out[at]=value.x();out[at+1]=value.y();out[at+2]=value.z(); }
}
