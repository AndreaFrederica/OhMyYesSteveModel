package cc.sirrus.ysmlib.image.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** TGA raster interpretation, independent of ImageIO's gray color conversion and origin limits. */
final class TgaDecoder {
  SceneImage read(ByteData source,ReadLimits limits) throws IOException {
    var cursor=new Cursor(source,source.size());
    int idLength=cursor.u8(),mapType=cursor.u8(),imageType=cursor.u8();
    int mapFirst=cursor.u16(),mapCount=cursor.u16(),mapBits=cursor.u8();
    int xOrigin=cursor.u16(),yOrigin=cursor.u16(),width=cursor.u16(),height=cursor.u16();
    int depth=cursor.u8(),descriptor=cursor.u8(),kind=imageType&7,attributeBits=descriptor&15;
    if(mapType>1 || !Set.of(1,2,3,9,10,11).contains(imageType) || descriptor>>>6==3)
      throw new IOException("Unsupported TGA image type, color map or interleave mode");
    SceneImageDecoder.budget(source,width,height,limits);
    if((kind==1 && (mapType!=1 || (depth!=8 && depth!=16)))
        || (kind==2 && !Set.of(15,16,24,32).contains(depth))
        || (kind==3 && depth!=8 && depth!=16)) throw new IOException("Unsupported TGA pixel layout");
    if(mapType==1 && (mapCount==0 || !Set.of(15,16,24,32).contains(mapBits)
        || mapFirst+mapCount>65536)) throw new IOException("Invalid TGA palette layout");
    if(mapCount>limits.maxElements() || (long)width*height*48+2L*source.size()+(long)mapCount*16>limits.maxBytes())
      throw new IOException("TGA palette/raster exceeds budget");
    int colorDepth=kind==1?mapBits:depth;
    int physicalAlpha=kind==3?(depth==16?8:0):(colorDepth==32?8:colorDepth==16?1:0);
    if(attributeBits!=0 && attributeBits!=physicalAlpha) throw new IOException("TGA attribute bits do not match pixels");
    var metadata=new LinkedHashMap<String,String>();
    metadata.put("samples","source-normalized");metadata.put("tga.xOrigin",Integer.toString(xOrigin));
    metadata.put("tga.yOrigin",Integer.toString(yOrigin));metadata.put("tga.descriptor",Integer.toString(descriptor));
    int alphaType=attributeBits>0?3:0;
    if(hasFooter(source)) {
      int footer=source.size()-26;cursor.limit=footer;
      long extension=unsigned32(source,footer),developer=unsigned32(source,footer+4);
      if(extension!=0) {
        int at=range(source,extension,495,footer);int size=u16(source,at);
        // 3ds Max's documented 494 size still contains all 495 bytes.
        if(size!=494 && size!=495) throw new IOException("Unsupported TGA extension size");
        cursor.limit=Math.min(cursor.limit,at);
        alphaType=source.unsigned(at+494);
        if(alphaType>4) throw new IOException("Unknown TGA alpha semantics: "+alphaType);
        metadata.put("tga.alphaType",Integer.toString(alphaType));
        int numerator=u16(source,at+478),denominator=u16(source,at+480);
        metadata.put("tga.gammaNumerator",Integer.toString(numerator));metadata.put("tga.gammaDenominator",Integer.toString(denominator));
        metadata.put("tga.aspectNumerator",Integer.toString(u16(source,at+474)));
        metadata.put("tga.aspectDenominator",Integer.toString(u16(source,at+476)));
        long correction=unsigned32(source,at+482),stamp=unsigned32(source,at+486),scan=unsigned32(source,at+490);
        if(correction!=0) {
          int start=range(source,correction,2048,footer);cursor.limit=Math.min(cursor.limit,start);
          metadata.put("tga.colorCorrectionOffset",Long.toString(correction));
          // 256 little-endian ARGB16 records, available verbatim without truncation.
          metadata.put("tga.colorCorrectionArgb16",Base64.getEncoder().encodeToString(slice(source,start,2048)));
        }
        if(stamp!=0) {
          int start=range(source,stamp,2,footer);
          range(source,stamp,2L+source.unsigned(start)*source.unsigned(start+1)*((depth+7)/8),footer);
          cursor.limit=Math.min(cursor.limit,start);metadata.put("tga.postageStampOffset",Long.toString(stamp));
        }
        if(scan!=0) {
          int start=range(source,scan,(long)height*4,footer);cursor.limit=Math.min(cursor.limit,start);
          metadata.put("tga.scanLineOffset",Long.toString(scan));
        }
      }
      if(developer!=0) {
        int start=range(source,developer,2,footer),count=u16(source,start);
        range(source,developer,2L+count*10L,footer);cursor.limit=Math.min(cursor.limit,start);
        for(int i=0;i<count;i++) {
          int entry=start+2+i*10;long offset=unsigned32(source,entry+2),length=unsigned32(source,entry+6);
          int data=range(source,offset,length,footer);
          if(length!=0) cursor.limit=Math.min(cursor.limit,data);
        }
        metadata.put("tga.developerDirectoryOffset",Long.toString(developer));
      }
    }
    if(alphaType>=2 && physicalAlpha==0) throw new IOException("TGA declares alpha without alpha storage");
    SceneImage.Alpha alpha=switch(alphaType) {
      case 2 -> SceneImage.Alpha.UNDEFINED;case 3 -> SceneImage.Alpha.STRAIGHT;
      case 4 -> SceneImage.Alpha.PREMULTIPLIED;default -> SceneImage.Alpha.OPAQUE;
    };
    cursor.skip(idLength);
    float[] palette=new float[mapType==1?mapCount*4:0],color=new float[4];
    if(mapType==1) for(int i=0;i<mapCount;i++) {
      color(cursor,mapBits,false,alpha,color);System.arraycopy(color,0,palette,i*4,4);
    }
    float[] rgba=new float[width*height*4];
    int[] rows=new int[height];int step=1<<(descriptor>>>6),rowIndex=0;
    for(int phase=0;phase<step;phase++) for(int y=phase;y<height;y+=step) rows[rowIndex++]=y;
    int pixel=0,total=width*height;
    while(pixel<total) {
      int packet=imageType>=8?cursor.u8():0;
      int count=imageType>=8?(packet&127)+1:1;boolean repeated=imageType>=8 && (packet&128)!=0;
      if(count>total-pixel) throw new IOException("TGA RLE packet exceeds raster");
      for(int i=0;i<count;i++,pixel++) {
        if(!repeated || i==0) {
          if(kind==1) {
            int index=(depth==8?cursor.u8():cursor.u16())-mapFirst;
            if(index<0 || index>=mapCount) throw new IOException("TGA palette index outside declared range");
            System.arraycopy(palette,index*4,color,0,4);
          } else color(cursor,depth,kind==3,alpha,color);
        }
        int x=pixel%width,y=rows[pixel/width];
        if((descriptor&16)!=0) x=width-1-x;
        if((descriptor&32)==0) y=height-1-y;
        System.arraycopy(color,0,rgba,(y*width+x)*4,4);
      }
    }
    int rgbBits=kind==3?8:colorDepth<24?5:8;
    return new SceneImage(SceneImage.Format.TGA,width,height,new FloatData(rgba),
        new IntData(rgbBits,rgbBits,rgbBits,alpha==SceneImage.Alpha.OPAQUE?0:physicalAlpha),alpha,metadata,source);
  }

  private static void color(Cursor cursor,int depth,boolean gray,SceneImage.Alpha alpha,float[] out) throws IOException {
    float a=1;
    if(gray) {
      out[0]=out[1]=out[2]=cursor.u8()/255f;
      // Standard 16-bit monochrome TGA is 8-bit intensity + 8-bit attribute, not uint16 gray.
      if(depth==16) a=cursor.u8()/255f;
    } else if(depth==15 || depth==16) {
      int value=cursor.u16();out[0]=((value>>>10)&31)/31f;out[1]=((value>>>5)&31)/31f;out[2]=(value&31)/31f;
      if(depth==16) a=value>>>15;
    } else {
      out[2]=cursor.u8()/255f;out[1]=cursor.u8()/255f;out[0]=cursor.u8()/255f;
      if(depth==32) a=cursor.u8()/255f;
    }
    out[3]=alpha==SceneImage.Alpha.OPAQUE?1:a;
  }
  private static boolean hasFooter(ByteData source) {
    byte[] magic="TRUEVISION-XFILE.\0".getBytes(StandardCharsets.US_ASCII);
    if(source.size()<26) return false;
    for(int i=0;i<magic.length;i++) if(source.get(source.size()-18+i)!=magic[i]) return false;
    return true;
  }
  private static int range(ByteData source,long offset,long length,int end) throws IOException {
    if(offset<18 || length<0 || offset+length>end || offset+length>source.size()) throw new IOException("TGA metadata offset outside source");
    return (int)offset;
  }
  private static int u16(ByteData source,int offset) { return source.unsigned(offset)|(source.unsigned(offset+1)<<8); }
  private static long unsigned32(ByteData source,int offset) { return Integer.toUnsignedLong(u16(source,offset)|(u16(source,offset+2)<<16)); }
  private static byte[] slice(ByteData source,int start,int count) { var data=new byte[count];source.view().position(start).get(data);return data; }
  private static final class Cursor {
    final ByteData data;int at,limit;
    Cursor(ByteData data,int limit) { this.data=data;this.limit=limit; }
    int u8() throws IOException { if(at>=limit) throw new IOException("Truncated TGA pixel/header data");return data.unsigned(at++); }
    int u16() throws IOException { return u8()|(u8()<<8); }
    void skip(int count) throws IOException { if((long)at+count>limit) throw new IOException("Truncated TGA image id");at+=count; }
  }
}
