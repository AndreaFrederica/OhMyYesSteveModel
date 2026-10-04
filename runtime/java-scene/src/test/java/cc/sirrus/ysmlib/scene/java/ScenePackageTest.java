package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScenePackageTest {
  final ScenePackageCodec codec=new ScenePackageCodec();
  final ReadLimits limits=ReadLimits.DEFAULT;
  final ScenePackage.Settings settings=new ScenePackage.Settings(.08,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD);
  static ByteData bytes(String value) { return new ByteData(value.getBytes(StandardCharsets.UTF_8)); }
  ScenePackage model() {
    return new ScenePackage(new ScenePackage.Source("avatar","models/avatar.pmx",ScenePackage.Format.PMX),settings,
        List.of(new ScenePackage.Source("dance","motion/dance.vmd",ScenePackage.Format.VMD)),
        List.of(new ScenePackage.Relocation("models/avatar.pmx","C:\\作者\\texture.png","textures/a%20#日.png")),
        Map.of("models/avatar.pmx",bytes("original model"),"motion/dance.vmd",bytes("original motion"),
            "textures/a%20#日.png",bytes("literal texture"),"textures/a #日.png",bytes("uri texture"),"LICENSE.txt",bytes("author terms")));
  }
  @Test void roundTripKeepsOriginalAssetsAndProducesDeterministicBytes() throws Exception {
    var source=model();var encoded=codec.write(source,limits);var decoded=codec.read(encoded,limits);
    assertEquals(source,decoded);assertEquals(encoded,codec.write(decoded,limits));
    assertThrows(UnsupportedOperationException.class,()->decoded.files().clear());
    var copy=decoded.files().get("models/avatar.pmx").copy();copy[0]=0;
    assertEquals(bytes("original model"),decoded.files().get("models/avatar.pmx"));
  }
  @Test void dccLiteralPathsAndGltfUrisResolveOnceWithinTheirOwner() throws Exception {
    var source=model();var paths=new PackageAssetResolver(source,"models/avatar.pmx",ScenePackage.ReferenceSyntax.FILE_PATH);
    var uris=new PackageAssetResolver(source,"models/avatar.pmx",ScenePackage.ReferenceSyntax.URI);
    assertEquals(bytes("literal texture"),paths.resolve("C:\\作者\\texture.png"));
    assertEquals(bytes("literal texture"),paths.resolve("..\\textures\\a%20#日.png"));
    assertEquals(bytes("uri texture"),uris.resolve("../textures/a%20%23%E6%97%A5.png"));
    assertEquals(bytes("literal texture"),uris.resolve("../textures/a%2520%23%E6%97%A5.png"));
    assertThrows(AssetFormatException.class,()->uris.resolve("../textures/a%20#日.png"));
    for(String reference:List.of("../../outside.png","%2e%2e/%2e%2e/outside.png","file:/tmp/a","https://example.org/a","//server/share/a","/%2e%2e/a","../textures/%FF.png"))
      assertThrows(AssetFormatException.class,()->uris.resolve(reference));
    assertThrows(AssetFormatException.class,()->new PackageAssetResolver(source,"motion/dance.vmd",ScenePackage.ReferenceSyntax.FILE_PATH).resolve("C:\\作者\\texture.png"));
  }
  @Test void manifestVersionPresenceDuplicateKeysAndUnknownBehaviorAreRejected() throws Exception {
    String manifest=manifest();
    for(String changed:List.of(manifest.replace("0.1.0-unstable","0.1.0-UNSTABLE"),
        manifest.replace("\"schema\":","\"schema\":\"duplicate\",\"schema\":"),
        manifest.replace("\"relocations\":[]","\"relocations\":[],\"futureBehavior\":true"),
        manifest.replace("\"id\":\"avatar\"","\"id\":7"),
        manifest.replace("\"animations\":[],",""),manifest.replace("avatar.pmx","missing.pmx")))
      assertThrows(IOException.class,()->codec.read(zip(Map.of("scene.json",bytes(changed),"avatar.pmx",bytes("source"))),limits));
  }
  @Test void zipDirectoryAndPayloadAreCheckedBeforePublication() throws Exception {
    var encoded=zip(Map.of("scene.json",bytes(manifest()),"avatar.pmx",bytes("source")));
    byte[] payload=encoded.copy();var b=ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    int central=0;for(int i=0;i<payload.length-4;i++) if(b.getInt(i)==0x02014b50) { central=i;break; }
    int local=b.getInt(central+42);payload[local+30]^=1; // central/local names disagree
    final byte[] badName=payload;
    assertThrows(IOException.class,()->codec.read(new ByteData(badName),limits));
    payload=encoded.copy();b=ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    local=b.getInt(central+42);int content=local+30+Short.toUnsignedInt(b.getShort(local+26))+Short.toUnsignedInt(b.getShort(local+28));
    payload[content]^=1; // STORED payload mismatch; central/local CRC left intact
    final byte[] corrupt=payload;
    assertThrows(IOException.class,()->codec.read(new ByteData(corrupt),limits));
    assertThrows(IOException.class,()->codec.read(new ByteData(Arrays.copyOf(encoded.copy(),encoded.size()-1)),limits));
    for(String bad:List.of("../outside","/absolute","a\\b","C:/absolute","a/./b"))
      assertThrows(IOException.class,()->codec.read(zip(Map.of("scene.json",bytes(manifest()),"avatar.pmx",bytes("source"),bad,bytes("bad"))),limits));
    assertThrows(IOException.class,()->codec.read(zip(Map.of("scene.json",bytes(manifest()),"avatar.pmx",bytes("source"),"a",bytes("file"),"a/b",bytes("nested"))),limits));
  }
  @Test void expandedBytesAndManifestStringsHaveCumulativeLimits() throws Exception {
    var source=new ScenePackage(new ScenePackage.Source("avatar","avatar.pmx",ScenePackage.Format.PMX),settings,List.of(),List.of(),
        Map.of("avatar.pmx",new ByteData(new byte[8000]),"other.bin",new ByteData(new byte[8000])));
    var encoded=codec.write(source,limits);assertTrue(encoded.size()<4000);
    assertThrows(IOException.class,()->codec.read(encoded,new ReadLimits(10000,1000,2000)));
    assertThrows(IOException.class,()->codec.write(source,new ReadLimits(10000,1000,2000)));
    assertThrows(IOException.class,()->codec.read(codec.write(model(),limits),new ReadLimits(10000,1000,8)));
    assertThrows(IOException.class,()->codec.read(codec.write(model(),limits),new ReadLimits(10000,2,100)));
  }
  @Test void packageReferencesMustFormAClosedUnambiguousGraph() {
    var base=model();
    assertThrows(IllegalArgumentException.class,()->new ScenePackage(base.model(),settings,List.of(base.model()),List.of(),base.files()));
    assertThrows(IllegalArgumentException.class,()->new ScenePackage(base.model(),settings,List.of(),List.of(new ScenePackage.Relocation("models/avatar.pmx","x","missing")),base.files()));
    assertThrows(IllegalArgumentException.class,()->new ScenePackage(base.model(),settings,List.of(),List.of(base.relocations().get(0),base.relocations().get(0)),base.files()));
    assertThrows(IllegalArgumentException.class,()->new ScenePackage(new ScenePackage.Source("motion","motion/dance.vmd",ScenePackage.Format.VMD),settings,List.of(),List.of(),base.files()));
  }
  static String manifest() { return "{\"schema\":\"ysmlib/scene-package\",\"version\":\"0.1.0-unstable\",\"model\":{\"id\":\"avatar\",\"path\":\"avatar.pmx\",\"format\":\"pmx\"},\"settings\":{\"metersPerUnit\":0.08,\"scene\":-1,\"fbxSkinSpace\":\"BIND_WORLD\"},\"animations\":[],\"relocations\":[]}"; }
  static ByteData zip(Map<String,ByteData> files) throws IOException {
    var output=new ByteArrayOutputStream();
    try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)) {
      for(var file:new TreeMap<>(files).entrySet()) {
        var entry=new ZipEntry(file.getKey());var crc=new CRC32();crc.update(file.getValue().copy());
        entry.setMethod(ZipEntry.STORED);entry.setSize(file.getValue().size());entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);zip.write(file.getValue().copy());zip.closeEntry();
      }
    }
    return new ByteData(output.toByteArray());
  }
}
