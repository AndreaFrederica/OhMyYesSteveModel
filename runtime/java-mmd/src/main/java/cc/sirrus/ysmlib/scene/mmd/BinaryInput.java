package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.nio.*;
import java.nio.charset.*;

/** All allocations are checked against source bounds before allocating. */
final class BinaryInput {
  static final Charset MS932=Charset.forName("windows-31j");
  private final ByteBuffer buffer;
  private final ReadLimits limits;
  private long elements;
  BinaryInput(ByteData bytes,ReadLimits limits) throws AssetFormatException {
    if(bytes.size()>limits.maxBytes()) throw new AssetFormatException("Source exceeds byte limit");
    this.buffer=bytes.view().order(ByteOrder.LITTLE_ENDIAN);this.limits=limits;
  }
  int remaining() { return buffer.remaining(); }
  int position() { return buffer.position(); }
  AssetFormatException error(String message) { return new AssetFormatException(message+" at byte "+position()); }
  void require(int size) throws AssetFormatException { if(size<0 || size>remaining()) throw error("Truncated input"); }
  int u8() throws AssetFormatException { require(1);return Byte.toUnsignedInt(buffer.get()); }
  int u16() throws AssetFormatException { require(2);return Short.toUnsignedInt(buffer.getShort()); }
  int i32() throws AssetFormatException { require(4);return buffer.getInt(); }
  long u32() throws AssetFormatException { return Integer.toUnsignedLong(i32()); }
  float f32() throws AssetFormatException { require(4);float v=buffer.getFloat();if(!Float.isFinite(v)) throw error("Non-finite float");return v; }
  Vec3 vec3() throws AssetFormatException { return new Vec3(f32(),f32(),f32()); }
  FloatData floats(int size) throws AssetFormatException {
    if(size<0 || (long)size*4>remaining()) throw error("Invalid float array");
    float[] out=new float[size];for(int i=0;i<size;i++) out[i]=f32();return new FloatData(out);
  }
  ByteData bytes(int size) throws AssetFormatException { require(size);byte[] out=new byte[size];buffer.get(out);return new ByteData(out); }
  String fixed(int size,Charset encoding) throws AssetFormatException {
    byte[] data=bytes(size).copy();int n=0;while(n<data.length && data[n]!=0) n++;
    return decode(ByteBuffer.wrap(data,0,n),encoding);
  }
  String string(Charset encoding) throws AssetFormatException {
    int size=i32();if(size<0 || size>limits.maxStringBytes()) throw error("String exceeds limit");
    return decode(bytes(size).view(),encoding);
  }
  String decode(ByteBuffer data,Charset encoding) throws AssetFormatException {
    try { return encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(data).toString(); }
    catch(CharacterCodingException e) { throw error("Invalid "+encoding.name()+" text"); }
  }
  int count(int minimumBytes) throws AssetFormatException { return count(u32(),minimumBytes); }
  int count(long count,int minimumBytes) throws AssetFormatException {
    if(count>limits.maxElements() || count<0 || (elements+=count)>limits.maxElements() || count*minimumBytes>remaining())
      throw error("Element count exceeds bounds");
    return (int)count;
  }
  int index(int size,boolean vertex) throws AssetFormatException {
    return switch(size) {
      case 1 -> { int v=u8();yield vertex?v:(byte)v; }
      case 2 -> { int v=u16();yield vertex?v:(short)v; }
      case 4 -> { int v=i32();if(vertex && v<0) throw error("Vertex index exceeds Java range");yield v; }
      default -> throw error("Invalid index size");
    };
  }
}
