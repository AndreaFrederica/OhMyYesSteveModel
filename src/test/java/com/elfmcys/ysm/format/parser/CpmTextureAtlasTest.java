package com.elfmcys.ysm.format.parser;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.cpm.CpmDocument;
import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.format.parser.pojo.model.CubeUv;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CpmTextureAtlasTest {
    @Test
    void legacyTextureSymbolsResolveToIndependentAtlasRegions() throws Exception {
        var files = new LinkedHashMap<String, ByteData>();
        files.put("model.json", bytes("""
                {"modelId":"atlas","modelName":"Atlas","bones":[
                  {"id":"red","texture":"tex.red","boxes":[{"coordinates":[0,0,0,2,2,2],"textureOffset":[0,0]}]},
                  {"id":"blue","texture":"tex.blue","boxes":[{"coordinates":[2,0,0,2,2,2],"textureOffset":[0,0]}]}
                ]}
                """));
        files.put("red.png", image(Color.RED));
        files.put("blue.png", image(Color.BLUE));
        CpmDocument source = YsmRuntime.cpm().read(files, ReadLimits.DEFAULT);

        var atlas = CpmTextureAtlas.build(source);
        var red = atlas.layout().region("tex.red");
        var blue = atlas.layout().region("tex.blue");
        assertNotEquals(red.u(), blue.u());
        assertTrue(atlas.layout().width() >= 128);
        var decoded = ImageIO.read(new java.io.ByteArrayInputStream(atlas.png()));
        assertEquals(Color.RED.getRGB(), decoded.getRGB(red.u(), red.v()));
        assertEquals(Color.BLUE.getRGB(), decoded.getRGB(blue.u(), blue.v()));

        var geometry = CpmYsmModelAdapter.build(source, atlas.layout());
        assertEquals(atlas.layout().width(), geometry.model().properties().textureWidth(), 0.001f);
        assertEquals(atlas.layout().height(), geometry.model().properties().textureHeight(), 0.001f);
    }

    @Test
    void perFaceUvRemappingPreservesFaceSize() {
        var source = CubeUv.box(new float[]{1.5f, 2.25f});
        var mapped = CpmYsmModelAdapter.remapUv(source, 64, 32);
        assertArrayEquals(new float[]{65.5f, 34.25f}, mapped.boxUv);
    }

    private static ByteData bytes(String value) { return new ByteData(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    private static ByteData image(Color color) throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, color.getRGB());
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return new ByteData(output.toByteArray());
    }
}
