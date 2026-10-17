package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.fbx.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FbxMappingProfileCodecTest {
  @Test void profileRoundTripsAndRejectsUnknownFields() {
    var profile=new FbxMappingProfile(1,"",Map.of(120,450),Map.of(77,
        new FbxMappingProfile.MaterialOverride("M_Body","../Texture/body.png","../Texture/body_n.png","","OPAQUE",.5f)));
    var codec=new FbxMappingProfileCodec();
    assertEquals(profile,codec.read(codec.write(profile)));
    assertThrows(IllegalArgumentException.class,()->codec.read(new ByteData("{\"schemaVersion\":1,\"sourceHash\":\"\",\"skinByMeshElement\":{},\"extra\":1}".getBytes())));
    assertThrows(IllegalArgumentException.class,()->codec.read(new ByteData("{\"schemaVersion\":1,\"sourceHash\":\"bad\",\"skinByMeshElement\":{}}".getBytes())));
    assertThrows(IllegalArgumentException.class,()->new FbxMappingProfile(1,"bad",Map.of()));
  }
}
