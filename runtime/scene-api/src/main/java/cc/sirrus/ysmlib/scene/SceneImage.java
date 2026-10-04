package cc.sirrus.ysmlib.scene;

import java.util.Map;
import java.util.Objects;

/**
 * Owned, top-left-origin 2D pixels in RGBA order. Samples retain the source transfer function:
 * decoding does not apply sRGB/gamma/ICC, premultiplication or tone mapping. Material usage owns
 * that choice (the same image can be used as both color and data). UNORM samples are normalized
 * without an intermediate eight-bit conversion. Original metadata remains in source.
 */
public record SceneImage(Format format,int width,int height,FloatData rgba,IntData componentBits,
                         Alpha alpha,Map<String,String> metadata,ByteData source) {
  public enum Format { PNG,JPEG,WEBP,AVIF,ZTX,BMP,TGA }
  public enum Alpha { OPAQUE,STRAIGHT,PREMULTIPLIED,UNDEFINED }
  public SceneImage {
    Objects.requireNonNull(format);Objects.requireNonNull(rgba);Objects.requireNonNull(componentBits);
    Objects.requireNonNull(alpha);Objects.requireNonNull(source);metadata=Map.copyOf(metadata);
    if(width<1 || height<1 || (long)width*height*4!=rgba.size() || componentBits.size()!=4)
      throw new IllegalArgumentException("Invalid scene image dimensions/components");
    for(int i=0;i<4;i++) if(componentBits.get(i)<0 || componentBits.get(i)>32)
      throw new IllegalArgumentException("Invalid image component precision");
  }
  /** Source-normalized value, not a display/sRGB conversion. */
  public float sample(int x,int y,int component) {
    if(x<0 || x>=width || y<0 || y>=height || component<0 || component>3)
      throw new IndexOutOfBoundsException("Image sample outside bounds");
    return rgba.get((y*width+x)*4+component);
  }
}
