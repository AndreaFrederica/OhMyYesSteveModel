import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.cpm.*;
import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Exercises only the actual shaded prerequisite, including relocated Gson and attribution resources. */
public final class CpmDistributionSmoke {
    private static ByteData bytes(String text) { return new ByteData(text.getBytes(StandardCharsets.UTF_8)); }
    public static void main(String[] args) throws Exception {
        var legacy=YsmRuntime.cpm().read(Map.of("model.json",bytes("{\"modelId\":\"sample\",\"modelName\":\"Sample\"}")),ReadLimits.DEFAULT);
        if(!(legacy instanceof CpmDocument.Legacy)||!legacy.metadata().name().equals("Sample")||legacy.compatibility().canEvaluateRequiredFeatures())
            throw new AssertionError("Shaded legacy CPM source parser mismatch");
        String geometry="{\"format_version\":\"1.10.0\",\"geometry.test\":{\"texturewidth\":64,\"textureheight\":64,\"bones\":[{\"name\":\"head_c\",\"pivot\":[0,24,0]}]}}";
        var remake=YsmRuntime.cpm().read(Map.of("model.json",bytes("{\"name\":\"New\",\"skeleton\":{\"type\":\"biped\"},\"parts\":[{\"name\":\"mesh\"}]}"),
            "mesh.json",bytes(geometry),"animation.js",bytes("throw 'must not execute';")),ReadLimits.DEFAULT);
        if(!(remake instanceof CpmDocument.Remake parsed)||parsed.parts().get(0).geometry().models().get(0).bones().get(0).pivot().y()!=24)
            throw new AssertionError("Shaded remake CPM source parser mismatch");
        try(var license=CpmDistributionSmoke.class.getResourceAsStream("/licenses/custom-player-model/LICENSE.txt");
            var notice=CpmDistributionSmoke.class.getResourceAsStream("/licenses/custom-player-model/NOTICE.txt")) {
            if(license==null||notice==null||!new String(license.readAllBytes(),StandardCharsets.UTF_8).contains("Copyright (c) 2020 Gamepiaynmo"))
                throw new AssertionError("CPM attribution missing from distribution");
        }
        System.out.println("Shaded Lib CPM source APIs and MIT attribution passed; no model code was executed.");
    }
}
