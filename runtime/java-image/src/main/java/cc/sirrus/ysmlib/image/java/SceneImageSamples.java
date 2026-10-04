package cc.sirrus.ysmlib.image.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.io.IOException;
import java.util.Objects;

/** Decode the transfer function before interpolation; shader-side decoding after texture() is too late. */
public final class SceneImageSamples {
  public FloatData prepare(SceneImage source,SceneImageUsage usage,ReadLimits limits) throws IOException {
    var shared=passthrough(source,usage,limits);if(shared!=null)return shared;
    float[] result=source.rgba().copy();
    for(int i=0;i<result.length;i+=4) {
      float alpha=result[i+3];
      if(usage.alpha()!=SceneImageUsage.Alpha.RAW && source.alpha()==SceneImage.Alpha.PREMULTIPLIED) {
        for(int channel=0;channel<3;channel++) result[i+channel]=alpha>0?result[i+channel]/alpha:0;
      }
      if(usage.transfer()==SceneImageUsage.Transfer.SRGB) for(int channel=0;channel<3;channel++) {
        float value=result[i+channel];
        result[i+channel]=value<=.04045f?value/12.92f:(float)Math.pow((value+.055)/1.055,2.4);
      }
      if(usage.alpha()==SceneImageUsage.Alpha.OPAQUE || usage.alpha()==SceneImageUsage.Alpha.STRAIGHT && source.alpha()==SceneImage.Alpha.OPAQUE)
        result[i+3]=1;
    }
    return FloatData.owned(result);
  }
  /** Returns existing immutable pixels when interpretation needs no work, otherwise null. Same admission checks as prepare. */
  public FloatData passthrough(SceneImage source,SceneImageUsage usage,ReadLimits limits) throws IOException {
    Objects.requireNonNull(source);Objects.requireNonNull(usage);Objects.requireNonNull(limits);
    long sampleBytes=source.rgba().storageBytes()+(long)source.rgba().size()*Float.BYTES*2;
    if((long)source.width()*source.height()>limits.maxElements() || sampleBytes>limits.maxBytes())
      throw new IOException("Material texture sample budget exceeded");
    if(usage.alpha()==SceneImageUsage.Alpha.STRAIGHT && source.alpha()==SceneImage.Alpha.UNDEFINED)
      throw new IOException("Undefined source attributes cannot be interpreted as opacity");
    // SOURCE_NUMERIC/LINEAR preserves the decoded RGB samples.  Keep the
    // compact UNORM8 backing storage when no alpha or premultiplication work is
    // required; expanding every 4K texture to Java floats was the main import
    // memory spike for MMD/VRM packages.
    boolean rgbUnchanged=usage.transfer()!=SceneImageUsage.Transfer.SRGB;
    boolean alphaUnchanged=usage.alpha()==SceneImageUsage.Alpha.RAW
        || usage.alpha()==SceneImageUsage.Alpha.STRAIGHT && source.alpha()!=SceneImage.Alpha.PREMULTIPLIED
        || usage.alpha()==SceneImageUsage.Alpha.OPAQUE && source.alpha()==SceneImage.Alpha.OPAQUE;
    return rgbUnchanged && alphaUnchanged?source.rgba():null;
  }
}
