package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectAssetResolverTest {
  @Test void relocatesForeignPathsAndEscapesLiteralUriCharactersWithoutOpeningSourceRoot() throws Exception {
    var requests=new ArrayList<String>();AssetResolver capability=r->{ requests.add(r);return new ByteData(new byte[]{1}); };
    var resolver=new ProjectAssetResolver(capability,Map.of("D:\\other\\track.vmd","motions/dance.vmd"),
        List.of(new ProjectAssetResolver.Root("C:\\MMD\\UserFile","assets"),new ProjectAssetResolver.Root("C:\\MMD\\UserFile\\Model","models")));
    var result=resolver.resolveWithOrigin("c:\\mmd\\userfile\\Model\\face #1%.png");
    assertEquals("models/face%20%231%25.png",result.projectReference());assertEquals("c:\\mmd\\userfile\\Model\\face #1%.png",result.sourceReference());
    resolver.resolve("d:\\OTHER\\track.vmd");resolver.resolve("./音楽/track.wav");
    assertEquals(List.of("models/face%20%231%25.png","motions/dance.vmd","%E9%9F%B3%E6%A5%BD/track.wav"),requests);
    assertThrows(AssetFormatException.class,()->resolver.resolve("Z:\\secret\\file"));
    assertThrows(AssetFormatException.class,()->resolver.resolve("../secret"));
    assertThrows(AssetFormatException.class,()->resolver.resolve("https://example.invalid/asset"));
    assertEquals(3,requests.size());
  }
  @Test void rejectsAmbiguousMapsAndDoesNotDecodePercentEscapesFromLiteralProjectPaths() throws Exception {
    assertThrows(IllegalArgumentException.class,()->new ProjectAssetResolver(AssetResolver.NONE,Map.of("C:/A","a","c:/a","b"),List.of()));
    assertThrows(IllegalArgumentException.class,()->new ProjectAssetResolver(AssetResolver.NONE,Map.of("source","../escape"),List.of()));
    var resolver=new ProjectAssetResolver(AssetResolver.NONE,Map.of(),List.of());
    assertEquals("%252e%252e/file",resolver.rebase("%2e%2e/file"));
  }
}
