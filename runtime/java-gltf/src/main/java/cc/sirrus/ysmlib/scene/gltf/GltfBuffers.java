package cc.sirrus.ysmlib.scene.gltf;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.gltf.JsonFields.*;

/** Accessors include interleaving, matrix-column padding, sparse overrides and normalized integers. */
final class GltfBuffers {
  record Accessor(int count,int components,int componentType,boolean normalized,String type,FloatData values) {}
  private final JsonObject root;private final ReadLimits limits;private final AssetResolver resolver;
  private final List<ByteData> buffers=new ArrayList<>();private final Map<Integer,Accessor> cache=new HashMap<>();
  final Map<String,ByteData> dependencies=new LinkedHashMap<>();private long byteBudget,scalarBudget;
  GltfBuffers(JsonObject root,ByteData binary,AssetResolver resolver,ReadLimits limits,int sourceBytes) throws IOException {
    this.root=root;this.limits=limits;this.resolver=resolver;this.byteBudget=sourceBytes;
    var definitions=array(root,"buffers");
    for(int i=0;i<definitions.size();i++) {
      var definition=definitions.get(i).getAsJsonObject();int length=nonnegative(integer(definition,"byteLength",-1),"buffer length");ByteData data;
      if(definition.has("uri")) data=dependency(definition.get("uri").getAsString());
      else { if(i!=0 || binary==null) throw new AssetFormatException("Missing GLB binary buffer");data=binary;if(data.size()-length>3) throw new AssetFormatException("Excess GLB buffer padding"); }
      if(data.size()<length) throw new AssetFormatException("Buffer is shorter than declared byteLength");
      buffers.add(data.size()==length?data:new ByteData(Arrays.copyOf(data.copy(),length)));
    }
  }
  ByteData dependency(String reference) throws IOException {
    if(dependencies.containsKey(reference)) return dependencies.get(reference);
    ByteData data;
    if(reference.startsWith("data:")) {
      int comma=reference.indexOf(',');if(comma<0 || !reference.substring(0,comma).endsWith(";base64")) throw new AssetFormatException("Only base64 data URIs are supported");
      long estimate=(long)(reference.length()-comma-1)*3/4;if(estimate>limits.maxBytes()-byteBudget) throw new AssetFormatException("Embedded dependency exceeds budget");
      try { data=new ByteData(Base64.getDecoder().decode(reference.substring(comma+1))); } catch(IllegalArgumentException e) { throw new AssetFormatException("Invalid base64 data URI",e); }
    } else data=Objects.requireNonNull(resolver.resolve(reference));
    byteBudget+=data.size();if(byteBudget>limits.maxBytes()) throw new AssetFormatException("Resolved dependencies exceed byte budget");
    dependencies.put(reference,data);return data;
  }
  ByteBuffer view(int index) {
    var views=array(root,"bufferViews");var view=views.get(index).getAsJsonObject();int buffer=integer(view,"buffer",-1);
    int offset=nonnegative(integer(view,"byteOffset",0),"bufferView offset"),length=nonnegative(integer(view,"byteLength",-1),"bufferView length");
    var bytes=buffers.get(buffer).view();if((long)offset+length>bytes.limit()) throw new IllegalArgumentException("BufferView exceeds buffer");
    bytes.position(offset);bytes.limit(offset+length);return bytes.slice().order(ByteOrder.LITTLE_ENDIAN);
  }
  private JsonObject definition(int index) { return array(root,"accessors").get(index).getAsJsonObject(); }
  Accessor accessor(int index) {
    if(cache.containsKey(index)) return cache.get(index);
    var a=definition(index);String type=string(a,"type","");int columns=type.startsWith("MAT")?switch(type) { case "MAT2"->2;case "MAT3"->3;case "MAT4"->4;default->throw new IllegalArgumentException("Unknown matrix type"); }:1;
    int rows=columns>1?columns:switch(type) { case "SCALAR"->1;case "VEC2"->2;case "VEC3"->3;case "VEC4"->4;default->throw new IllegalArgumentException("Unknown accessor type"); };
    int components=rows*columns,component=integer(a,"componentType",-1),size=componentSize(component),count=nonnegative(integer(a,"count",-1),"accessor count");
    boolean normalized=bool(a,"normalized",false);if(normalized && (component==5125 || component==5126)) throw new IllegalArgumentException("Invalid normalized component type");
    long scalars=(long)count*components;scalarBudget+=scalars;
    if(count>limits.maxElements() || scalars>Integer.MAX_VALUE || scalarBudget*4>limits.maxBytes()) throw new IllegalArgumentException("Decoded accessor budget exceeded");
    int columnStride=columns==1?rows*size:((rows*size+3)&~3),elementSize=columnStride*columns;
    float[] values=new float[(int)scalars];int offset=nonnegative(integer(a,"byteOffset",0),"accessor offset");
    if(offset%size!=0) throw new IllegalArgumentException("Misaligned accessor offset");
    if(a.has("bufferView")) {
      int viewId=integer(a,"bufferView",-1);var view= view(viewId);var viewDef=array(root,"bufferViews").get(viewId).getAsJsonObject();int stride=integer(viewDef,"byteStride",elementSize);
      if(stride<elementSize || stride%size!=0 || viewDef.has("byteStride") && (stride<4 || stride>252 || stride%4!=0)) throw new IllegalArgumentException("Invalid accessor stride");
      checkRange(offset,count,stride,elementSize,view.limit());
      for(int i=0;i<count;i++) for(int c=0;c<columns;c++) for(int r=0;r<rows;r++) values[i*components+c*rows+r]=read(view,offset+i*stride+c*columnStride+r*size,component,normalized);
    } else if(offset!=0) throw new IllegalArgumentException("Accessor offset without bufferView");
    if(a.has("sparse")) {
      var sparse=object(a,"sparse");int n=nonnegative(integer(sparse,"count",-1),"sparse count");if(n>count) throw new IllegalArgumentException("Sparse count exceeds accessor");
      var indices=object(sparse,"indices");var data=object(sparse,"values");int indexType=integer(indices,"componentType",-1);
      if(indexType!=5121 && indexType!=5123 && indexType!=5125) throw new IllegalArgumentException("Invalid sparse index type");
      var indexView=view(integer(indices,"bufferView",-1));var valueView=view(integer(data,"bufferView",-1));
      int io=nonnegative(integer(indices,"byteOffset",0),"sparse index offset"),vo=nonnegative(integer(data,"byteOffset",0),"sparse value offset"),is=componentSize(indexType);
      checkRange(io,n,is,is,indexView.limit());checkRange(vo,n,elementSize,elementSize,valueView.limit());
      if(io%is!=0 || vo%size!=0) throw new IllegalArgumentException("Misaligned sparse accessor");
      long previous=-1;
      for(int i=0;i<n;i++) {
        long vertex=rawInteger(indexView,io+i*is,indexType);if(vertex<=previous || vertex>=count) throw new IllegalArgumentException("Invalid sparse index order/range");previous=vertex;
        for(int c=0;c<columns;c++) for(int r=0;r<rows;r++) values[(int)vertex*components+c*rows+r]=read(valueView,vo+i*elementSize+c*columnStride+r*size,component,normalized);
      }
    }
    var result=new Accessor(count,components,component,normalized,type,new FloatData(values));cache.put(index,result);return result;
  }
  IntData indices(int index,int vertexCount) {
    var accessor=accessor(index);if(accessor.components()!=1 || accessor.normalized() || accessor.componentType()!=5121 && accessor.componentType()!=5123 && accessor.componentType()!=5125)
      throw new IllegalArgumentException("Invalid primitive index accessor");
    // Valid vertex indices are bounded below 2^24 by ReadLimits, so float storage is exact for every accepted index.
    if(vertexCount>16_777_216) throw new IllegalArgumentException("Mesh vertex count exceeds exact index representation");
    int[] out=new int[accessor.count()];for(int i=0;i<out.length;i++) { float v=accessor.values().get(i);if(v<0 || v>=vertexCount) throw new IllegalArgumentException("Primitive index out of range");out[i]=(int)v; }
    return new IntData(out);
  }
  private static void checkRange(int offset,int count,int stride,int size,int length) {
    if((long)offset+(count==0?0:(long)(count-1)*stride+size)>length) throw new IllegalArgumentException("Accessor exceeds bufferView");
  }
  private static int componentSize(int type) { return switch(type) { case 5120,5121->1;case 5122,5123->2;case 5125,5126->4;default->throw new IllegalArgumentException("Invalid component type"); }; }
  private static long rawInteger(ByteBuffer b,int offset,int type) { return switch(type) { case 5120->b.get(offset);case 5121->Byte.toUnsignedInt(b.get(offset));case 5122->b.getShort(offset);case 5123->Short.toUnsignedInt(b.getShort(offset));case 5125->Integer.toUnsignedLong(b.getInt(offset));default->throw new IllegalArgumentException("Not an integer component"); }; }
  private static float read(ByteBuffer b,int offset,int type,boolean normalized) {
    if(type==5126) { float v=b.getFloat(offset);if(!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite accessor float");return v; }
    long v=rawInteger(b,offset,type);if(!normalized) return v;
    return switch(type) { case 5120->Math.max(v/127f,-1);case 5121->v/255f;case 5122->Math.max(v/32767f,-1);case 5123->v/65535f;default->throw new IllegalArgumentException("Invalid normalized type"); };
  }
}
