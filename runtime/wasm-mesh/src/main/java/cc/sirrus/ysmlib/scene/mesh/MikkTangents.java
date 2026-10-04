package cc.sirrus.ysmlib.scene.mesh;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.*;
import java.io.*;
import java.nio.*;
import java.util.function.Function;

/** Original MikkTSpace, default 180 degree threshold. No JNI, subprocess, filesystem or graphics API. */
public final class MikkTangents {
  private static final class Code {
    static final WasmModule MODULE=load();
    static final Function<Instance,Machine> FACTORY=MachineFactoryCompiler.compile(MODULE);
    private static WasmModule load() {
      try(var input=MikkTangents.class.getResourceAsStream("/cc/sirrus/ysmlib/mesh/mikktspace.wasm")) {
        if(input==null) throw new IOException("Bundled MikkTSpace reactor is missing");return Parser.parse(input);
      } catch(IOException e) { throw new UncheckedIOException(e); }
    }
  }
  /** Triangle corners, not source vertex indices. Output is one tangent XYZW per input corner. */
  public FloatData generate(FloatData positions,FloatData normals,FloatData uv,ReadLimits limits) {
    int corners=positions.size()/3;
    if(positions.size()%9!=0 || normals.size()!=positions.size() || uv.size()!=corners*2)
      throw new IllegalArgumentException("MikkTSpace requires matching triangle corner positions/normals/UV");
    if(corners>limits.maxElements() || (long)corners*48>limits.maxBytes())
      throw new IllegalArgumentException("MikkTSpace input/output budget exceeded");
    if(corners==0) return FloatData.EMPTY;
    try(var wasi=WasiPreview1.builder().build()) {
      var instance=Instance.builder(Code.MODULE).withMachineFactory(Code.FACTORY)
          .withImportValues(ImportValues.builder().addFunction(wasi.toHostFunctions()).build()).build();
      if(instance.export("ysm_tangent_abi").apply()[0]!=1) throw new IllegalStateException("MikkTSpace ABI mismatch");
      int input=(int)instance.export("malloc").apply(corners*32)[0],output=0;
      if(input==0) throw new IllegalArgumentException("MikkTSpace input memory exhausted");
      try {
        output=(int)instance.export("malloc").apply(corners*16)[0];
        if(output==0) throw new IllegalArgumentException("MikkTSpace output memory exhausted");
        var bytes=ByteBuffer.allocate(corners*32).order(ByteOrder.LITTLE_ENDIAN);
        for(int v=0;v<corners;v++) {
          for(int c=0;c<3;c++) bytes.putFloat(positions.get(v*3+c));
          double length=0;for(int c=0;c<3;c++) length+=(double)normals.get(v*3+c)*normals.get(v*3+c);
          length=Math.sqrt(length);
          for(int c=0;c<3;c++) bytes.putFloat(length==0?0:(float)(normals.get(v*3+c)/length));
          bytes.putFloat(uv.get(v*2)).putFloat(uv.get(v*2+1));
        }
        instance.memory().write(input,bytes.array());
        int result=(int)instance.export("ysm_tangents").apply(input,output,corners,limits.maxBytes())[0];
        if(result<0) throw new IllegalArgumentException(result==-2?"MikkTSpace scratch budget exceeded":"MikkTSpace failed: "+result);
        float[] values=new float[corners*4];
        ByteBuffer.wrap(instance.memory().readBytes(output,corners*16)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
        return new FloatData(values);
      } finally { if(output!=0) instance.export("free").apply(output);instance.export("free").apply(input); }
    }
  }
}
