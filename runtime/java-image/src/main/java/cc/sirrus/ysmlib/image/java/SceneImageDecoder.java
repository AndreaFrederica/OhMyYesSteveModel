package cc.sirrus.ysmlib.image.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.twelvemonkeys.imageio.plugins.bmp.BMPImageReaderSpi;
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import java.awt.color.ColorSpace;
import java.io.*;
import java.nio.ByteOrder;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** General-scene samples; deliberately separate from the legacy MC RGBA8 image wire. */
public final class SceneImageDecoder {
  public SceneImage read(ByteData source,String hint,ReadLimits limits) throws IOException {
    Objects.requireNonNull(source);Objects.requireNonNull(limits);
    if(source.size()>limits.maxBytes()) throw new IOException("Encoded scene image exceeds byte budget");
    String formatHint=Objects.requireNonNullElse(hint,"").toLowerCase(Locale.ROOT);
    try {
      var format=detect(source,formatHint);
      if(format==SceneImage.Format.TGA) return new TgaDecoder().read(source,limits);
      if(format==SceneImage.Format.ZTX || format==SceneImage.Format.AVIF) return legacy(source,format,limits);
      if(format==SceneImage.Format.BMP) validateBmp(source,limits);
      try(var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(source.copy()))) {
        ImageReader reader=switch(format) {
          case BMP -> new BMPImageReaderSpi().createReaderInstance();
          case WEBP -> new WebPImageReaderSpi().createReaderInstance();
          default -> standardReader(format);
        };
        try {
          // The image's transfer metadata must not override a model material's color/data usage.
          reader.setInput(stream,false,true);
          int sourceWidth=reader.getWidth(0),sourceHeight=reader.getHeight(0);
          var raw=reader.getRawImageType(0);
          if(raw==null) throw new IOException("No source image sample layout: "+format);
          int colorType=raw.getColorModel().getColorSpace().getType();
          if(colorType!=ColorSpace.TYPE_RGB && colorType!=ColorSpace.TYPE_GRAY)
            throw new IOException("Scene image color layout needs an explicit conversion: "+colorType);
          boolean gray=raw.getColorModel().getNumColorComponents()==1;
          boolean alpha=raw.getColorModel().hasAlpha();
          boolean compact=componentBits(raw,gray,alpha)<=8;
          int sample=1;
          // Choose the largest power-of-two source stride that fits the
          // remaining import budget.  UVs are normalized, so this preserves
          // material mapping while preventing a small preview from decoding
          // an unnecessary 4K/8K raster.
          while (sample < 1024) {
            int candidateWidth=(sourceWidth+sample-1)/sample;
            int candidateHeight=(sourceHeight+sample-1)/sample;
            if (fitsBudget(source,candidateWidth,candidateHeight,limits,compact?12:40)) break;
            sample <<= 1;
          }
          int width=(sourceWidth+sample-1)/sample,height=(sourceHeight+sample-1)/sample;
          budget(source,width,height,limits,compact?12:40);
          var param=reader.getDefaultReadParam();
          if (sample>1) param.setSourceSubsampling(sample,sample,0,0);
          param.setDestinationType(raw);
          var image=reader.read(0,param);
          if(image.getWidth()!=width || image.getHeight()!=height) throw new IOException("Image dimensions changed during decode");
          var cm=image.getColorModel();var raster=image.getRaster();
          gray=cm.getNumColorComponents()==1;alpha=cm.hasAlpha();
          float[] pixels=compact?null:new float[width*height*4];
          byte[] packed=compact?new byte[width*height*4]:null;
          float[] components=null;Object data=null;
          for(int y=0,offset=0;y<height;y++) for(int x=0;x<width;x++) {
            data=raster.getDataElements(x,y,data);
            components=cm.getNormalizedComponents(data,components,0);
            // getNormalizedComponents does not perform ColorSpace.toRGB/getRGB (gray gamma, ICC,
            // and 16-bit detail must survive). It does unassociate premultiplied layouts.
            float r=components[0],g=components[gray?0:1],b=components[gray?0:2],a=alpha?components[cm.getNumColorComponents()]:1;
            if (compact) {
              packed[offset++]=quantize8(r);packed[offset++]=quantize8(g);
              packed[offset++]=quantize8(b);packed[offset++]=quantize8(a);
            } else {
              pixels[offset++]=r;pixels[offset++]=g;pixels[offset++]=b;pixels[offset++]=a;
            }
          }
          var bits=new IntData(cm.getComponentSize(0),cm.getComponentSize(gray?0:1),cm.getComponentSize(gray?0:2),
              alpha?cm.getComponentSize(cm.getNumColorComponents()):0);
          var metadata=new LinkedHashMap<String,String>();
          metadata.put("samples","source-normalized");metadata.put("colorLayout",gray?"gray":"rgb");
          if (sample>1) { metadata.put("sourceWidth",Integer.toString(sourceWidth));metadata.put("sourceHeight",Integer.toString(sourceHeight));metadata.put("downsampleFactor",Integer.toString(sample)); }
          return new SceneImage(format,width,height,compact?FloatData.ownedNormalized8(packed):FloatData.owned(pixels),bits,
              alpha?SceneImage.Alpha.STRAIGHT:SceneImage.Alpha.OPAQUE,metadata,source);
        } finally { reader.dispose(); }
      }
    } catch(IllegalArgumentException | IndexOutOfBoundsException invalid) {
      throw new IOException("Invalid scene image",invalid);
    }
  }

  static void budget(ByteData source,int width,int height,ReadLimits limits) throws IOException {
    budget(source,width,height,limits,40);
  }
  static void budget(ByteData source,int width,int height,ReadLimits limits,int bytesPerPixel) throws IOException {
    long pixels=(long)width*height;
    // Includes the output and its immutable ownership copy (32 bytes/pixel),
    // a bounded decoder raster and the encoded input copy. 40 bytes/pixel
    // keeps two 4K RGBA images admissible under a 1 GiB scene budget while
    // still charging substantially more than the retained representation.
    long estimated=Math.addExact(Math.multiplyExact(pixels,bytesPerPixel),Math.multiplyExact(2L,source.size()));
    if(width<1 || height<1 || pixels>limits.maxElements() || estimated>limits.maxBytes())
      throw new IOException("Decoded scene image exceeds pixel/byte budget: dimensions="+width+"x"+height
          +", pixels="+pixels+", encodedBytes="+source.size()+", estimatedBytes="+estimated
          +", estimatedBytesPerPixel="+bytesPerPixel+", maxBytes="+limits.maxBytes()+", maxPixels="+limits.maxElements());
  }
  private static boolean fitsBudget(ByteData source,int width,int height,ReadLimits limits,int bytesPerPixel) {
    long pixels=(long)width*height;
    return width>=1 && height>=1 && pixels<=limits.maxElements()
        && pixels<=((long)limits.maxBytes()-2L*source.size())/bytesPerPixel;
  }

  private static int componentBits(ImageTypeSpecifier raw,boolean gray,boolean alpha) {
    var cm=raw.getColorModel();
    return Math.max(cm.getComponentSize(0),Math.max(cm.getComponentSize(gray?0:1),
        Math.max(cm.getComponentSize(gray?0:2),alpha?cm.getComponentSize(cm.getNumColorComponents()):0)));
  }
  private static byte quantize8(float value) {
    return (byte)Math.max(0,Math.min(255,Math.round(value*255f)));
  }

  private static ImageReader standardReader(SceneImage.Format format) throws IOException {
    var readers=ImageIO.getImageReadersByFormatName(format.name());
    while(readers.hasNext()) {
      var reader=readers.next();
      // External host plugins cannot silently alter decoding or bypass our supported layouts.
      if(reader.getClass().getName().startsWith("com.sun.imageio.plugins.")) return reader;
      reader.dispose();
    }
    throw new IOException("Missing standard Java image decoder: "+format);
  }

  private static SceneImage legacy(ByteData source,SceneImage.Format format,ReadLimits limits) throws IOException {
    // Current AVIF provider is an RGBA8 presentation codec; a source-depth-aware path is required
    // before accepting AVIF scene data. Never silently truncate 10/12-bit images here.
    if(format==SceneImage.Format.AVIF) throw new IOException("AVIF scene sample precision is not yet available");
    var codec=new JavaImageProvider();var info=codec.probe(source.view());
    budget(source,info.width(),info.height(),limits);
    var bytes=codec.decode(source.view(),info);var pixels=new float[bytes.length];
    for(int i=0;i<bytes.length;i++) pixels[i]=(bytes[i]&255)/255f;
    return new SceneImage(format,info.width(),info.height(),FloatData.owned(pixels),new IntData(8,8,8,8),
        SceneImage.Alpha.STRAIGHT,Map.of("samples","source-normalized"),source);
  }

  private static SceneImage.Format detect(ByteData data,String hint) throws IOException {
    if(starts(data,0,137,80,78,71,13,10,26,10)) return SceneImage.Format.PNG;
    if(starts(data,0,255,216,255)) return SceneImage.Format.JPEG;
    if(starts(data,0,66,77)) return SceneImage.Format.BMP; // includes MMD .sph/.spa with BMP contents
    if(starts(data,0,82,73,70,70) && starts(data,8,87,69,66,80)) return SceneImage.Format.WEBP;
    if(starts(data,0,122,116,120,49)) return SceneImage.Format.ZTX;
    if(starts(data,4,102,116,121,112)) return SceneImage.Format.AVIF;
    if(Set.of("tga",".tga","image/tga","image/x-tga","image/x-targa").contains(hint)
        || hint.endsWith(".tga") || hint.endsWith(".sph") || hint.endsWith(".spa")) return SceneImage.Format.TGA;
    throw new IOException("Unsupported or unidentified scene image: "+hint);
  }
  private static boolean starts(ByteData data,int start,int... bytes) {
    if((long)start+bytes.length>data.size()) return false;
    for(int i=0;i<bytes.length;i++) if(data.unsigned(start+i)!=bytes[i]) return false;
    return true;
  }

  private static void validateBmp(ByteData source,ReadLimits limits) throws IOException {
    if(source.size()<26) throw new IOException("Truncated BMP header");
    var b=source.view().order(ByteOrder.LITTLE_ENDIAN);long offset=Integer.toUnsignedLong(b.getInt(10));
    long header=Integer.toUnsignedLong(b.getInt(14));
    if(header<12 || header+14>source.size() || offset<header+14 || offset>source.size()) throw new IOException("Invalid BMP offsets");
    if(header>=40) {
      int bits=Short.toUnsignedInt(b.getShort(28));long colors=Integer.toUnsignedLong(b.getInt(46));
      if(colors>limits.maxElements() || colors*4+2L*source.size()>limits.maxBytes()) throw new IOException("BMP palette budget exceeded");
      if(bits<=8 && colors>(1L<<bits)) throw new IOException("Invalid BMP palette length");
      int width=b.getInt(18),height=b.getInt(22);
      if(width<1 || height==Integer.MIN_VALUE) throw new IOException("Invalid BMP dimensions");
      long pixels=(long)width*Math.abs(height);
      if(pixels*48+colors*8+2L*source.size()>limits.maxBytes()) throw new IOException("BMP combined raster/palette budget exceeded");
      long compression=Integer.toUnsignedLong(b.getInt(30));
      // Embedded PNG/JPEG would introduce a second unchecked dimension/allocation domain.
      if(compression==4 || compression==5) throw new IOException("Embedded PNG/JPEG BMP requires a bounded nested decoder");
    }
  }
}
