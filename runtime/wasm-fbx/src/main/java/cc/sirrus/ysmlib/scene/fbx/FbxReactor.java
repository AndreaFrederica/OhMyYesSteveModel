package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Isolated memory-only FBX session. The public scene adapter owns conversion and dependency capabilities. */
final class FbxReactor implements AutoCloseable {
  private static final class Code {
    static final WasmModule MODULE=load();
    static final Function<Instance,Machine> FACTORY=MachineFactoryCompiler.compile(MODULE);
    static WasmModule load() {
      try(var input=FbxReactor.class.getResourceAsStream("/cc/sirrus/ysmlib/fbx/ufbx.wasm")) {
        if(input==null) throw new IOException("Bundled ufbx reactor is missing");return Parser.parse(input);
      } catch(IOException e) { throw new UncheckedIOException(e); }
    }
  }
  private final WasiPreview1 wasi;
  private final Instance instance;
  private final ReadLimits limits;
  private boolean closed;

  FbxReactor(ByteData source,ReadLimits limits) throws AssetFormatException {
    this.limits=Objects.requireNonNull(limits);Objects.requireNonNull(source);
    if(source.size()==0 || source.size()>limits.maxBytes()) throw new AssetFormatException("FBX input byte budget exceeded");
    wasi=WasiPreview1.builder().build();
    try {
      instance=Instance.builder(Code.MODULE).withMachineFactory(Code.FACTORY)
          .withImportValues(ImportValues.builder().addFunction(wasi.toHostFunctions()).build()).build();
      if(call("ysm_fbx_abi")!=2) throw new IllegalStateException("FBX reactor ABI mismatch");
      int pointer=call("malloc",source.size());if(pointer==0) throw new AssetFormatException("FBX input memory exhausted");
      try {
        instance.memory().write(pointer,source.copy());
        // The source budget is also the upper bound for the bounded ufbx result and
        // snapshot.  Fixed 64/128 MiB caps rejected real Blender/Unity FBX files whose
        // retained DOM is larger than the encoded file even though the caller granted a
        // valid import budget.
        checked("ysm_fbx_load",pointer,source.size(),limits.maxBytes(),limits.maxBytes(),limits.maxElements(),limits.maxStringBytes());
      } finally { instance.export("free").apply(pointer); }
    } catch(RuntimeException|Error|AssetFormatException failure) { wasi.close();throw failure; }
  }
  synchronized JsonObject source() throws AssetFormatException { return snapshot(true,false); }
  synchronized JsonObject evaluate(int stack,double seconds) throws AssetFormatException {
    return evaluate(stack,seconds,false);
  }
  synchronized JsonObject evaluate(int stack,double seconds,boolean followMesh) throws AssetFormatException {
    ensureOpen();if(!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite FBX time");
    checked("ysm_fbx_evaluate",stack,Double.doubleToRawLongBits(seconds));return snapshot(false,followMesh);
  }
  private JsonObject snapshot(boolean source,boolean followMesh) throws AssetFormatException {
    ensureOpen();checked("ysm_fbx_snapshot",source?1:0,followMesh?1:0);int size=call("ysm_fbx_output_size");
    if(size<0 || size>limits.maxBytes()) throw new AssetFormatException("FBX snapshot output budget exceeded");
    int pointer=call("ysm_fbx_output");var bytes=instance.memory().readBytes(pointer,size);
    try { return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject(); }
    catch(JsonParseException|IllegalStateException e) { throw new AssetFormatException("Invalid internal FBX snapshot",e); }
  }
  private int call(String name,long... arguments) { return (int)instance.export(name).apply(arguments)[0]; }
  private void checked(String name,long... arguments) throws AssetFormatException {
    if(call(name,arguments)>=0) return;
    byte[] bytes=instance.memory().readBytes(call("ysm_fbx_error"),2048);int end=0;while(end<bytes.length&&bytes[end]!=0) end++;
    throw new AssetFormatException(new String(bytes,0,end,StandardCharsets.UTF_8));
  }
  private void ensureOpen() { if(closed) throw new IllegalStateException("FBX reactor is closed"); }
  @Override public synchronized void close() { if(closed) return;closed=true;try { instance.export("ysm_fbx_destroy").apply(); } finally { wasi.close(); } }
}
