package cc.sirrus.ysmlib.image.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneImageDecoderTest {
  private final SceneImageDecoder decoder=new SceneImageDecoder();
  @Test void independentPillowPixelsForRasterFormatsOrientationsPalettesAndRle() throws Exception {
    for(String file:List.of("rgb.tga","rgba-rle.tga","gray-alpha.tga","palette.tga","rgb.bmp","palette.bmp","mono.bmp",
        "rgba.png","rgba.webp","rgb.jpg","origin-00.tga","origin-10.tga","origin-20.tga","origin-30.tga","rle8.bmp")) {
      var source=resource(file);var image=decoder.read(source,file,ReadLimits.DEFAULT);var expected=resource(file+".rgba");
      assertEquals(expected.size(),image.rgba().size(),file);
      for(int i=0;i<expected.size();i++) assertEquals(expected.unsigned(i)/255f,image.rgba().get(i),file.endsWith(".jpg")?2f/255:1e-6f,file+" pixel component "+i);
      assertEquals(source,image.source());
    }
    assertEquals(SceneImage.Format.BMP,decoder.read(resource("rgb.bmp"),"sphere.sph",ReadLimits.DEFAULT).format());
  }
  @Test void sixteenBitAndGrayPngPreserveSamplesWithoutDisplayConversion() throws Exception {
    var image=new BufferedImage(3,1,BufferedImage.TYPE_USHORT_GRAY);
    image.getRaster().setSample(0,0,0,1);image.getRaster().setSample(1,0,0,32768);image.getRaster().setSample(2,0,0,65534);
    var bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"png",bytes));
    var decoded=decoder.read(new ByteData(bytes.toByteArray()),"",ReadLimits.DEFAULT);
    assertEquals(new IntData(16,16,16,0),decoded.componentBits());
    assertEquals(1f/65535,decoded.sample(0,0,0),1e-8);assertEquals(32768f/65535,decoded.sample(1,0,1),1e-8);
    assertEquals(65534f/65535,decoded.sample(2,0,2),1e-8);
    assertThrows(ReadOnlyBufferException.class,()->decoded.rgba().view().put(0,1));
    var gray=new BufferedImage(1,1,BufferedImage.TYPE_BYTE_GRAY);gray.getRaster().setSample(0,0,0,128);
    bytes.reset();assertTrue(ImageIO.write(gray,"png",bytes));
    assertEquals(128f/255,decoder.read(new ByteData(bytes.toByteArray()),"",ReadLimits.DEFAULT).sample(0,0,0),1e-7);
  }
  @Test void tgaInterleaveAndPaletteOriginRetainSourceSampleMeaning() throws Exception {
    // File scan lines 0,2,4,1,3; origin at top. Sixteenth descriptor bit is a real horizontal origin.
    var b=tga(2,1,5,24,0x60);b.put(new byte[]{0,0,10,0,0,30,0,0,50,0,0,20,0,0,40});
    var decoded=decode(b);
    for(int y=0;y<5;y++) assertEquals((y+1)*10/255f,decoded.sample(0,y,0),1e-7);
    b=tga(1,2,1,16,0x20);b.put(1,(byte)1);b.putShort(3,(short)300);b.putShort(5,(short)2);b.put(7,(byte)24);
    b.put(new byte[]{0,0,(byte)255,0,(byte)255,0});b.putShort((short)301).putShort((short)300);
    decoded=decode(b);assertEquals(1,decoded.sample(0,0,1));assertEquals(1,decoded.sample(1,0,0));
    b=tga(2,1,1,16,0x21);b.putShort((short)0x8421);decoded=decode(b);
    assertEquals(1f/31,decoded.sample(0,0,0),1e-7);assertEquals(new IntData(5,5,5,1),decoded.componentBits());
  }
  @Test void tgaExtensionAlphaGammaAndUndefinedDataAreExplicit() throws Exception {
    for(int alphaType=0;alphaType<=4;alphaType++) {
      var b=tga(2,1,1,32,0x28);b.put(new byte[]{32,48,64,(byte)128});
      int extension=b.position();b.putShort((short)495);b.position(extension+478);b.putShort((short)22).putShort((short)10);
      b.position(extension+494);b.put((byte)alphaType);b.putInt(extension).putInt(0);b.put("TRUEVISION-XFILE.\0".getBytes(StandardCharsets.US_ASCII));
      var decoded=decode(b);
      assertEquals(alphaType<2?1:128f/255,decoded.sample(0,0,3),1e-7);
      assertEquals(64f/255,decoded.sample(0,0,0),1e-7,"Decoder must not unpremultiply source TGA samples");
      assertEquals("22",decoded.metadata().get("tga.gammaNumerator"));
      assertEquals(switch(alphaType) { case 2->SceneImage.Alpha.UNDEFINED;case 3->SceneImage.Alpha.STRAIGHT;case 4->SceneImage.Alpha.PREMULTIPLIED;default->SceneImage.Alpha.OPAQUE; },decoded.alpha());
    }
  }
  @Test void malformedAndOversizedTexturesFailBeforeDecodePublication() throws Exception {
    assertThrows(IOException.class,()->decoder.read(resource("rgb.bmp"),"",new ReadLimits(100,1,1)));
    var oversized=tga(2,65535,65535,32,8);assertThrows(IOException.class,()->decode(oversized));
    var rle=tga(10,1,1,24,0);rle.put((byte)0x81).put(new byte[]{1,2,3});assertThrows(IOException.class,()->decode(rle));
    var palette=tga(1,1,1,8,0);palette.put(1,(byte)1);palette.putShort(5,(short)1);palette.put(7,(byte)24);
    palette.put(new byte[]{1,2,3,2});assertThrows(IOException.class,()->decode(palette));
    byte[] bmp=resource("rgb.bmp").copy();ByteBuffer.wrap(bmp).order(ByteOrder.LITTLE_ENDIAN).putInt(46,Integer.MAX_VALUE);
    assertThrows(IOException.class,()->decoder.read(new ByteData(bmp),"",ReadLimits.DEFAULT));
    var unknown=tga(2,1,1,24,0xC0);assertThrows(IOException.class,()->decode(unknown));
    for(int length=0;length<18;length++) {
      var truncated=new ByteData(new byte[length]);assertThrows(IOException.class,()->decoder.read(truncated,"tga",ReadLimits.DEFAULT));
    }
  }
  @Test void oldMcImageContractRemainsSeparateFromDccTextures() throws Exception {
    var legacy=new JavaImageProvider();
    assertThrows(IOException.class,()->legacy.probe(resource("rgb.bmp").view()));
    assertThrows(IOException.class,()->legacy.probe(resource("rgb.tga").view()));
    assertEquals(SceneImage.Format.BMP,decoder.read(resource("rgb.bmp"),"",ReadLimits.DEFAULT).format());
  }
  @Test void eightBitImagesDownsampleToTheRemainingBudget() throws Exception {
    var sourceImage=new BufferedImage(64,64,BufferedImage.TYPE_4BYTE_ABGR);
    var bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(sourceImage,"png",bytes));
    var decoded=decoder.read(new ByteData(bytes.toByteArray()),"",new ReadLimits(100_000,1024,1024));
    assertEquals(32,decoded.width());assertEquals(32,decoded.height());
    assertEquals("2",decoded.metadata().get("downsampleFactor"));
    assertEquals("64",decoded.metadata().get("sourceWidth"));
  }
  private SceneImage decode(ByteBuffer b) throws Exception { return decoder.read(new ByteData(Arrays.copyOf(b.array(),b.position())),"tga",ReadLimits.DEFAULT); }
  private static ByteBuffer tga(int type,int width,int height,int bits,int descriptor) {
    var b=ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);b.put(2,(byte)type);b.putShort(12,(short)width);b.putShort(14,(short)height);
    b.put(16,(byte)bits);b.put(17,(byte)descriptor);b.position(18);return b;
  }
  private static ByteData resource(String name) throws IOException {
    try(var in=SceneImageDecoderTest.class.getResourceAsStream("/scene-image-oracle/"+name)) {
      if(in==null) throw new IOException("Missing image oracle: "+name);return new ByteData(in.readAllBytes());
    }
  }
}
