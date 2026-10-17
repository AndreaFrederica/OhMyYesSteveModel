package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import cc.sirrus.ysmlib.cpm.CpmValue.*;
import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CpmReaderTest {
    private final JavaCpmProvider reader=new JavaCpmProvider();
    private static ByteData bytes(String text) { return new ByteData(text.getBytes(StandardCharsets.UTF_8)); }
    private CpmDocument read(String main) throws Exception { return reader.read(Map.of("model.json",bytes(main)),ReadLimits.DEFAULT); }
    static String geometry(String version) {
        String body="""
          "bones":[{"name":"head_c","pivot":[0,18,0],"rotation":[10,20,30],
          "cubes":[{"origin":[-4,18,-4],"size":[8,8,0],"uv":[12,16],"inflate":0.25,"mirror":true}]}]
          """;
        return version.equals("1.10.0")?"{\"format_version\":\"1.10.0\",\"geometry.test\":{\"texturewidth\":64,\"textureheight\":32,"+body+"}}"
          :"{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":32},"+body+"}]}";
    }
    private static Map<String,ByteData> remake(String extra) {
        return Map.of("model.json",bytes("{\"name\":\"Sample\",\"author\":[\"A\",\"B\"],\"skeleton\":{\"type\":\"biped\",\"attachments\":{\"helmet\":[]},\"left_arm\":[]},\"parts\":[{\"name\":\"mesh\",\"texture\":\"tex\",\"double_face\":true}]"+extra+"}"),
                      "mesh.json",bytes(geometry("1.12.0")),"tex.png",new ByteData(new byte[]{1,2,3}));
    }
    @Test void retainsLegacyDynamicSourceAndAuthoredCoordinates() throws Exception {
        var doc=(CpmDocument.Legacy)read("""
          {"modelId":"sample","modelName":"Legacy","author":"A", // comments occur upstream
           "tickVars":{"count":["float",0,"tvl.count+1"]},"variables":{"blink":"fmod(age,60)<5"},
           "bones":[{"id":"mesh","parent":"head","position":[0,"sin(age)",0],"physics":[0,5,0.8,0,0],
             "boxes":[{"coordinates":[-4,18,-4,8,8,0],"textureOffset":[3,7],"mirror":true}],
             "quads":[{"coordinates":[1,2,3,4,5]}]}],"future":{"x":123}}
          """);
        assertEquals("none",((CpmDocument.Legacy)read("{\"modelId\":\"empty\",\"modelName\":\"Empty\",\"bones\":[{\"id\":\"root\"}]}" )).bones().get(0).parent());
        assertEquals(List.of(-4d,18d,-4d,8d,8d,0d),doc.bones().get(0).boxes().get(0).coordinates());
        assertEquals(List.of(1d,2d,3d,4d,5d),doc.bones().get(0).quads().get(0).coordinates());
        var position=(ArrayValue)doc.bones().get(0).source().fields().get("position");
        assertEquals("sin(age)",((StringValue)position.values().get(1)).value());
        assertTrue(doc.source().fields().containsKey("tickVars"));assertTrue(doc.source().fields().containsKey("future"));
        assertFalse(doc.compatibility().canEvaluateRequiredFeatures());
        assertThrows(UnsupportedOperationException.class,()->doc.source().fields().clear());
        assertThrows(UnsupportedOperationException.class,()->doc.bones().clear());
    }
    @Test void bothBedrockLayoutsRetainTheSameGeometryWithoutAxisConversion() throws Exception {
        var a=reader.readGeometry(bytes(geometry("1.10.0")),ReadLimits.DEFAULT);
        var b=reader.readGeometry(bytes(geometry("1.12.0")),ReadLimits.DEFAULT);
        var early=reader.readGeometry(bytes(geometry("1.10.0").replace("1.10.0","1.8.0")),ReadLimits.DEFAULT);
        assertEquals(a.models(),early.models());
        assertEquals(a.models().get(0).bones(),b.models().get(0).bones());
        var bone=b.models().get(0).bones().get(0);
        assertEquals(new CpmGeometry.Vector(0,18,0),bone.pivot());
        assertEquals(new CpmGeometry.Vector(10,20,30),bone.rotation());
        assertEquals(0,bone.cubes().get(0).size().z());assertTrue(bone.cubes().get(0).mirror());
        assertEquals(.25,bone.cubes().get(0).inflate());
    }
    @Test void readsClosureAndKeepsDefaultListsDistinctFromEmptyLists() throws Exception {
        var files=new LinkedHashMap<>(remake(""));String script="function init(){throw 'must not execute';} while(true){}";
        files.put("animation.js",bytes(script));
        var doc=(CpmDocument.Remake)reader.read(files,ReadLimits.DEFAULT);
        assertEquals(script,doc.animationScript().orElseThrow());assertEquals("tex.png",doc.parts().get(0).packageTexturePath());
        assertTrue(doc.skeleton().leftArm().isPresent());assertTrue(doc.skeleton().leftArm().get().isEmpty());
        assertTrue(doc.skeleton().rightArm().isEmpty());assertTrue(doc.skeleton().attachments().get("helmet").isEmpty());
        assertTrue(doc.parts().get(0).doubleFace());assertEquals(List.of("A","B"),doc.metadata().authors());
        files.clear();assertEquals(4,doc.files().size());assertFalse(doc.compatibility().canEvaluateRequiredFeatures());
    }
    @Test void detectsSourceFamiliesAndRejectsAmbiguousOrBrokenReferences() throws Exception {
        assertTrue(((CpmDocument.Legacy)read("{\"modelId\":\"default\",\"modelName\":\"Default\"}")).bones().isEmpty());
        assertEquals(CpmDocument.Format.CUSTOM_MODEL,reader.detect(bytes("{\"modelId\":\"x\"}"),ReadLimits.DEFAULT).orElseThrow());
        assertTrue(reader.detect(bytes("{\"format_version\":\"1.12.0\"}"),ReadLimits.DEFAULT).isEmpty());
        assertThrows(AssetFormatException.class,()->read("{\"modelId\":\"x\",\"parts\":[]}"));
        assertThrows(AssetFormatException.class,()->read("{\"modelId\":\"x\",\"modelName\":\"X\",\"bones\":[{\"id\":\"a\",\"parent\":\"later\"},{\"id\":\"later\"}]}"));
        var missing=new LinkedHashMap<>(remake(""));missing.remove("mesh.json");
        assertThrows(AssetFormatException.class,()->reader.read(missing,ReadLimits.DEFAULT));
        assertThrows(AssetFormatException.class,()->reader.read(remake(",\"addons\":{\"missing\":\"helmet\"}"),ReadLimits.DEFAULT));
        var duplicate=new LinkedHashMap<>(remake(""));duplicate.put("model.json",bytes("{\"name\":\"a\",\"name\":\"b\",\"parts\":[]}"));
        assertThrows(AssetFormatException.class,()->reader.read(duplicate,ReadLimits.DEFAULT));
    }
    @Test void rejectsCyclesInvalidNumbersAndUnsupportedVersions() throws Exception {
        String cycle=geometry("1.12.0").replace("\"pivot\":[0,18,0]","\"parent\":\"head_c\",\"pivot\":[0,18,0]");
        assertThrows(AssetFormatException.class,()->reader.readGeometry(bytes(cycle),ReadLimits.DEFAULT));
        assertEquals(1, reader.readGeometry(bytes(geometry("1.12.0").replace("1.12.0","1.16.0")),ReadLimits.DEFAULT).models().size());
        assertThrows(AssetFormatException.class,()->reader.readGeometry(bytes(geometry("1.12.0").replace("64","1e999")),ReadLimits.DEFAULT));
        assertThrows(AssetFormatException.class,()->reader.readGeometry(bytes(geometry("1.12.0").replace("64","0")),ReadLimits.DEFAULT));
    }
    @Test void appliesBudgetsToWholeClosureStringsNodesAndNesting() throws Exception {
        assertThrows(AssetFormatException.class,()->reader.read(remake(""),new ReadLimits(50,100,50)));
        assertThrows(AssetFormatException.class,()->reader.detect(bytes("{\"name\":\"very long string\"}"),new ReadLimits(100,100,5)));
        assertThrows(AssetFormatException.class,()->reader.detect(bytes("{\"a\":[0,1,2,3]}"),new ReadLimits(100,4,50)));
        assertThrows(AssetFormatException.class,()->reader.detect(bytes("{\"a\":"+"[".repeat(130)+"0"+"]".repeat(130)+"}"),ReadLimits.DEFAULT));
        assertThrows(AssetFormatException.class,()->reader.detect(new ByteData(new byte[]{(byte)0xff}),ReadLimits.DEFAULT));
        var traversal=new LinkedHashMap<>(remake(""));traversal.put("../outside.png",ByteData.EMPTY);
        assertThrows(AssetFormatException.class,()->reader.read(traversal,ReadLimits.DEFAULT));
        var sharedBudget=new ReadLimits(4096,65,1024);
        assertDoesNotThrow(()->reader.detect(remake("").get("model.json"),sharedBudget));
        assertDoesNotThrow(()->reader.readGeometry(remake("").get("mesh.json"),sharedBudget));
        assertThrows(AssetFormatException.class,()->reader.read(remake(""),sharedBudget));
    }
    @Test void keepsPerFaceUvAndUnknownGeometryFieldsInert() throws Exception {
        String source=geometry("1.12.0").replace("\"uv\":[12,16]","\"uv\":{\"north\":{\"uv\":[1,2]}},\"unknown\":7");
        var files=new LinkedHashMap<>(remake(""));files.put("mesh.json",bytes(source));
        var doc=(CpmDocument.Remake)reader.read(files,ReadLimits.DEFAULT);
        var cube=doc.parts().get(0).geometry().models().get(0).bones().get(0).cubes().get(0);
        assertInstanceOf(ObjectValue.class,cube.uv());assertTrue(cube.source().fields().containsKey("unknown"));
        assertTrue(doc.compatibility().features().stream().anyMatch(f->f.id().equals("cpm.per-face-uv")));
    }
    @Test void retainsLegacyDuplicateMembersAndBindsDuplicateBoneParentsByOccurrence() throws Exception {
        var doc=(CpmDocument.Legacy)read("""
          {"modelId":"dup","modelName":"First","modelName":"Last",
           "tickVars":{"b":["boolean",false,"!tvl.b"],"numericInitial":["boolean",0,"false"]},
           "bones":[{"id":"a"},{"id":"child1","parent":"a"},{"id":"a"},{"id":"child2","parent":"a"}]}
          """);
        assertEquals("Last",doc.metadata().name());assertEquals(4,doc.bones().size());
        assertEquals(0,doc.bones().get(1).parentIndex());assertEquals(2,doc.bones().get(3).parentIndex());
        assertEquals(2,doc.source().members().stream().filter(m->m.name().equals("modelName")).count());
        assertEquals(2,doc.compatibility().diagnostics().size());
    }
    @Test void compilesBothFormatsToStaticCommonIrWithoutEvaluatingSource() throws Exception {
        var legacy=(CpmDocument.Legacy)read("""
          {"modelId":"x","modelName":"X","bones":[{"id":"root","boxes":[{"coordinates":[-1,2,3,4,5,6],"textureOffset":[7,8],"mirror":true}]}]}
          """);
        var a=CpmSceneCompiler.compile(legacy);
        assertEquals(1,a.bones().size());assertEquals(1,a.primitives().size());
        assertEquals(CpmCompiledModel.Kind.CUBE,a.primitives().get(0).kind());
        assertEquals(new CpmCompiledModel.Vec3(-1,2,3),a.primitives().get(0).origin());
        var remake=(CpmDocument.Remake)reader.read(remake(""),ReadLimits.DEFAULT);
        var b=CpmSceneCompiler.compile(remake);
        assertEquals(1,b.bones().size());assertEquals(1,b.primitives().size());
        assertEquals("tex",b.textures().get("tex").symbol());
        assertTrue(b.primitives().get(0).source() instanceof CpmValue.ObjectValue);
    }
    @Test void canonicalCompilerOwnsPlayerSkeletonAndLegacyCoordinateAdaptation() throws Exception {
        var legacy=(CpmDocument.Legacy)read("""
          {"modelId":"x","modelName":"X","bones":[{"id":"mesh","parent":"head",
           "boxes":[{"coordinates":[-1,2,3,4,5,6],"textureOffset":[7,8]}]}]}
          """);
        var legacyCompiled=CpmSceneCompiler.compileCanonical(legacy);
        assertEquals(new CpmCompiledModel.Vec3(0,24,0),legacyCompiled.bones().stream()
                .filter(b->b.name().equals("Head")).findFirst().orElseThrow().pivot());
        assertEquals(new CpmCompiledModel.Vec3(-1,-7,-3),legacyCompiled.primitives().get(0).origin());

        var remake=(CpmDocument.Remake)reader.read(remake(""),ReadLimits.DEFAULT);
        var remakeCompiled=CpmSceneCompiler.compileCanonical(remake);
        var remakeBone=remakeCompiled.bones().stream().filter(b->b.name().equals("head_c")).findFirst().orElseThrow();
        assertEquals("Head",remakeCompiled.bones().get(remakeBone.parent()).name());
        assertEquals(24,remakeCompiled.bones().size());
        assertEquals(64,remakeCompiled.textureWidth());
    }

    @Test void remakeRootBonesBindAllHumanoidSlots() throws Exception {
        var files=new LinkedHashMap<>(remake(""));
        files.put("mesh.json",bytes("""
          {"format_version":"1.12.0","minecraft:geometry":[
            {"description":{"identifier":"geometry.test","texture_width":64,"texture_height":64},
             "bones":[
               {"name":"head_c","pivot":[0,18,0]},
               {"name":"body_c","pivot":[0,10,0]},
               {"name":"left_arm_c","pivot":[3,17,0]},
               {"name":"right_arm_c","pivot":[-3,17,0]},
               {"name":"left_leg_c","pivot":[2,9,0]},
               {"name":"right_leg_c","pivot":[-2,9,0]}
             ]}
          ]}
          """));
        var compiled=CpmSceneCompiler.compileCanonical((CpmDocument.Remake)reader.read(files,ReadLimits.DEFAULT));
        var parents=new LinkedHashMap<String,String>();
        for(var name:List.of("head_c","body_c","left_arm_c","right_arm_c","left_leg_c","right_leg_c")) {
            var bone=compiled.bones().stream().filter(b->b.name().equals(name)).findFirst().orElseThrow();
            parents.put(name,bone.parent()<0?null:compiled.bones().get(bone.parent()).name());
        }
        assertEquals(Map.of("head_c","Head","body_c","UpperBody","left_arm_c","LeftArm",
                "right_arm_c","RightArm","left_leg_c","LeftLeg","right_leg_c","RightLeg"),parents);
    }

    @Test void modernSuffixRootsUseModelBoneNamespaceBeforeDuplicateEquipmentAttachments() throws Exception {
        var files = new LinkedHashMap<>(remake(""));
        files.put("model.json", bytes("""
          {"name":"SuffixRoots","skeleton":{"type":"biped",
           "attachments":{"chestplate_left":["left_arm_c"],"chestplate_left":["right_arm_c"]}},
           "parts":[{"name":"mesh","texture":"tex"}]}
          """));
        files.put("mesh.json", bytes("""
          {"format_version":"1.12.0","minecraft:geometry":[
            {"description":{"identifier":"geometry.test","texture_width":64,"texture_height":64},
             "bones":[{"name":"left_arm_c","pivot":[3,22,0]},
                       {"name":"right_arm_c","pivot":[-3,22,0]}]}
          ]}
          """));
        var compiled = CpmSceneCompiler.compileCanonical((CpmDocument.Remake) reader.read(files, ReadLimits.DEFAULT));
        var parents = new LinkedHashMap<String,String>();
        for (var name : List.of("left_arm_c", "right_arm_c")) {
            var bone = compiled.bones().stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow();
            parents.put(name, compiled.bones().get(bone.parent()).name());
        }
        assertEquals(Map.of("left_arm_c", "LeftArm", "right_arm_c", "RightArm"), parents);
    }

    @Test void modernAuthoredLowercaseHostNamesKeepTheirExactParent() throws Exception {
        var files = new LinkedHashMap<>(remake(""));
        files.put("mesh.json", bytes("""
          {"format_version":"1.12.0","minecraft:geometry":[
            {"description":{"identifier":"geometry.test","texture_width":64,"texture_height":64},
             "bones":[
               {"name":"body","pivot":[0,10,0]},
               {"name":"upperBody","parent":"body","pivot":[0,10,0],
                "cubes":[{"origin":[-1,9,-1],"size":[2,2,2],"uv":[0,0]}]}
             ]}
          ]}
          """));
        var compiled = CpmSceneCompiler.compileCanonical((CpmDocument.Remake)reader.read(files, ReadLimits.DEFAULT));
        var child = compiled.bones().stream().filter(b -> b.name().equals("upperBody")).findFirst().orElseThrow();
        assertEquals("body", compiled.bones().get(child.parent()).name());
    }

    @Test void modernOrdinaryCustomRootDoesNotInferHostFromItsName() throws Exception {
        var files = new LinkedHashMap<>(remake(",\"skeleton\":{\"type\":\"biped\",\"param\":{\"body_pivot_height\":10}}"));
        files.put("mesh.json", bytes("""
          {"format_version":"1.12.0","minecraft:geometry":[
            {"description":{"identifier":"geometry.test","texture_width":64,"texture_height":64},
             "bones":[{"name":"body_sword","pivot":[0,10,0],
               "cubes":[{"origin":[-1,9,-1],"size":[2,2,2],"uv":[0,0]}]}]}
          ]}
          """));
        var doc = (CpmDocument.Remake) reader.read(files, ReadLimits.DEFAULT);
        var compiled = CpmSceneCompiler.compileCanonical(doc);
        var root = compiled.bones().stream().filter(b -> b.name().equals("body_sword")).findFirst().orElseThrow();
        assertEquals(-1, root.parent());
        try (var session = reader.createPoseSession(doc).orElseThrow()) {
            var pose = session.tick(0, Map.of()).get("body_sword");
            assertArrayEquals(new double[]{0, -14, 0}, pose.position(), 1e-9);
        }
    }

    @Test void modernQuadrupedCustomRootUsesTheUpstreamLegHost() throws Exception {
        var files = new LinkedHashMap<>(remake(""));
        files.put("model.json", bytes("{\"name\":\"Quad\",\"skeleton\":{\"type\":\"quadruped\",\"param\":{\"leg_length\":6,\"leg_interval\":8,\"leg_offset\":1,\"body_length\":12}},\"parts\":[{\"name\":\"mesh\",\"texture\":\"tex\"}]}"));
        files.put("mesh.json", bytes("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":64,\"texture_height\":32},\"bones\":[{\"name\":\"left_front_leg_c\",\"pivot\":[4,6,13],\"cubes\":[{\"origin\":[3,0,12],\"size\":[2,6,2],\"uv\":[0,0]}]}]}]}"));
        var doc=(CpmDocument.Remake)reader.read(files, ReadLimits.DEFAULT);
        var root=CpmSceneCompiler.compileCanonical(doc).bones().stream().filter(b->b.name().equals("left_front_leg_c")).findFirst().orElseThrow();
        assertEquals("left_front_leg", CpmSceneCompiler.compileCanonical(doc).bones().get(root.parent()).name());
        try (var session=reader.createPoseSession(doc).orElseThrow()) {
            var pose=session.tick(0, Map.of()).get("left_front_leg_c");
            assertArrayEquals(new double[]{0,0,0}, pose.position(), 1e-9);
        }
    }

    @Test void legacyNestedBonesKeepLocalPositionForGeometryAndUseAccumulatedPivot() throws Exception {
        var legacy=(CpmDocument.Legacy)read("""
          {"modelId":"x","modelName":"X","bones":[
            {"id":"head_c","parent":"head","position":[0,0,0],
             "boxes":[{"coordinates":[-1,0,0,2,2,2],"textureOffset":[0,0]}]},
            {"id":"hair","parent":"head_c","position":[4,8,1],
             "boxes":[{"coordinates":[0,0,0,1,1,1],"textureOffset":[0,0]}]}
          ]}
          """);
        var compiled=CpmSceneCompiler.compileCanonical(legacy);
        var head=compiled.bones().stream().filter(b->b.name().equals("head_c")).findFirst().orElseThrow();
        var hair=compiled.bones().stream().filter(b->b.name().equals("hair")).findFirst().orElseThrow();
        assertEquals(new CpmCompiledModel.Vec3(0,0,0),head.pivot());
        assertEquals(new CpmCompiledModel.Vec3(0,0,0),hair.pivot());
        // Legacy custom bones retain local ModelRenderer geometry and pose;
        // the parent hierarchy applies the accumulated transform at runtime.
        var hairPrimitive=compiled.primitives().stream().filter(p->p.bone()==hair.index()).findFirst().orElseThrow();
        assertEquals(new CpmCompiledModel.Vec3(0,-1,-1),hairPrimitive.origin());
    }
    @Test void evaluatesLegacyPresentationExpressionsWithoutMinecraftObjects() throws Exception {
        var doc=(CpmDocument.Legacy)read("""
          {"modelId":"x","modelName":"X","bones":[{"id":"root","position":["sin(age)",2,3],"visible":"age>2","scale":[1,"fmod(age,2)",1]}]}
          """);
        var pose=CpmLegacyPoseEvaluator.evaluate(doc,Map.of("age",3d)).get("root");
        assertEquals(Math.sin(3),pose.position()[0],1e-9);assertTrue(pose.visible());assertEquals(1,pose.scale()[1],1e-9);
    }

    @Test void legacyPoseRetainsAuthoredLocalPositionForModelRendererHierarchy() throws Exception {
        var doc=(CpmDocument.Legacy)read("""
          {"modelId":"x","modelName":"X","bones":[
            {"id":"root","parent":"head","position":[4,8,1]}]}
          """);
        try (var session=reader.createPoseSession(doc).orElseThrow()) {
            var pose=session.tick(0,Map.of()).get("root");
            assertArrayEquals(new double[]{4,8,1},pose.position(),1e-9);
        }
    }
    @Test void legacySkeletonPointsRemainAbsoluteModelRendererAttachments() throws Exception {
        var doc=(CpmDocument.Legacy)read("""
          {"modelId":"x","modelName":"X",
           "skeleton":{"left_arm_all":[-3,0,0],"right_leg_all":[2,-1,4]},
           "bones":[]}
          """);
        var poses=CpmLegacyPoseEvaluator.evaluate(doc,Map.of());
        // updateSkeleton() calls setRotationPoint(-x,-y,z).  The pose bridge
        // stores the correction relative to vanilla (+/-5,2,0) and
        // (+/-1.9,12,0), which JavaModelState then applies once.
        assertArrayEquals(new double[]{2,-2,0},poses.get("LeftArm").position(),1e-9);
        assertArrayEquals(new double[]{0.1,-11,4},poses.get("RightLeg").position(),1e-9);
        var compiled=CpmSceneCompiler.compileCanonical(doc);
        assertEquals(new CpmCompiledModel.Vec3(-5,26,0),compiled.bones().stream()
                .filter(b->b.name().equals("LeftArm")).findFirst().orElseThrow().pivot());
    }

    @Test void legacyPlayerBonesRemainIndependentRendererRoots() throws Exception {
        var doc=(CpmDocument.Legacy)read("""
          {"modelId":"x","modelName":"X","bones":[
            {"id":"arm_piece","parent":"left_arm","boxes":[
              {"coordinates":[0,0,0,1,1,1],"textureOffset":[0,0]}]}
          ]}
          """);
        var compiled=CpmSceneCompiler.compileCanonical(doc);
        var left=compiled.bones().stream().filter(b->b.name().equals("LeftArm")).findFirst().orElseThrow();
        var head=compiled.bones().stream().filter(b->b.name().equals("Head")).findFirst().orElseThrow();
        var piece=compiled.bones().stream().filter(b->b.name().equals("arm_piece")).findFirst().orElseThrow();
        assertEquals(-1,left.parent(),"Legacy PlayerBone roots must not inherit synthetic YSM grouping pivots");
        assertEquals(-1,head.parent(),"Every Legacy PlayerBone is an independent ModelRenderer");
        assertEquals("LeftArm",compiled.bones().get(piece.parent()).name());
    }
    @Test void advancesLegacyTickVariablesInSourceOrder() throws Exception {
        var doc=(CpmDocument.Legacy)read("""
          {"modelId":"x","modelName":"X","tickVars":{"n":["float",0,"n+1"],"b":["boolean",false,"n>1"]}}
          """);
        var session=new CpmLegacySession(doc);assertEquals(1,session.tick(Map.of()).get("n"));assertEquals(2,session.tick(Map.of()).get("n"));
    }
    @Test void exposesIsolatedProviderPoseSessions() throws Exception {
        var doc=(CpmDocument.Legacy)read("""
          {"modelId":"x","modelName":"X","tickVars":{"n":["float",0,"n+1"]},
           "bones":[{"id":"root","position":["n",0,0],"visible":"n>0"}]}
          """);
        try (var session=reader.createPoseSession(doc).orElseThrow()) {
            assertEquals(1,session.tick(0,Map.of()).get("root").position()[0]);
            var pose=session.tick(1,Map.of()).get("root");
            assertEquals(2,pose.position()[0]);assertTrue(pose.visible());
        }
    }
    @Test void executesRemakeAnimationThroughRestrictedProxy() {
        var bone=new CpmJavascriptRuntime.Bone();
        try(var runtime=new CpmJavascriptRuntime("function tick(e,m){m.getBone('head').setScaleX(e.getAge());}",Map.of("head",bone))) {
            runtime.tick(2);assertEquals(2,runtime.snapshot().get("head").scale()[0]);
        }
    }

    @Test void remakeScriptStartsAtAuthoredRotationAndSettersRemainAbsolute() throws Exception {
        var files = new LinkedHashMap<>(remake(""));
        files.put("animation.js", bytes("function update(e,m){m.getBone('head_c').setRotationY(45);}"));
        var document = (CpmDocument.Remake) reader.read(files, ReadLimits.DEFAULT);
        try (var session = new JavaCpmPoseSession(document)) {
            var pose = session.tick(0, Map.of()).get("head_c");
            // Modern pose output is the complete ScriptBone/source rotation;
            // JavaModelState consumes it once at the render boundary.
            assertArrayEquals(new double[]{45, 10, 30}, pose.rotation(), 1e-9);
        }
    }

    @Test void remakeWithoutAnimationStillEmitsAuthoredBonePose() throws Exception {
        var files = new LinkedHashMap<>(remake(""));
        var document = (CpmDocument.Remake) reader.read(files, ReadLimits.DEFAULT);
        try (var session = new JavaCpmPoseSession(document)) {
            var pose = session.tick(0, Map.of()).get("head_c");
            assertNotNull(pose);
            // ScriptBone exposes the complete authored pixel position.  The
            // Modern YSM adapter consumes it once at the render boundary.
            assertArrayEquals(new double[]{0, -6, 0}, pose.position(), 1e-9);
            assertArrayEquals(new double[]{20, 10, 30}, pose.rotation(), 1e-9);
        }
    }
    @Test void remakeScriptPositionUsesCpmPixelDeltaAtAttachedRoot() throws Exception {
        var files = new LinkedHashMap<>(remake(""));
        files.put("animation.js", bytes("function update(e,m){m.getBone('head_c').setPositionX(4);}"));
        var document = (CpmDocument.Remake) reader.read(files, ReadLimits.DEFAULT);
        try (var session = new JavaCpmPoseSession(document)) {
            var pose = session.tick(0, Map.of()).get("head_c");
            assertArrayEquals(new double[]{4, -6, 0}, pose.position(), 1e-9);
        }
    }
    @Test void scriptReceivesInputsAndWritesRenderState() {
        var bone=new CpmJavascriptRuntime.Bone();
        try(var runtime=new CpmJavascriptRuntime("function tick(e,m){var b=m.getBone('head');b.setColor(1,e.getInput('health'),0);b.setAlpha(.5);b.setEmissive(true);}",Map.of("head",bone))) {
            runtime.tick(2,Map.of("health",.25));
            var value=runtime.snapshot().get("head");
            assertEquals(.25,value.color()[1]); assertEquals(.5,value.alpha()); assertTrue(value.emissive());
        }
    }

    @Test void scriptItemProxyUsesHostRegistryNames() {
        cc.sirrus.ysmlib.cpm.CpmItemNames.register(7, "minecraft:diamond_sword");
        var bone = new CpmJavascriptRuntime.Bone();
        try (var runtime = new CpmJavascriptRuntime(
                "function tick(e,m){m.getBone('head').setVisible(e.getRightHandItem().getItem() == 'minecraft:diamond_sword');}",
                Map.of("head", bone))) {
            runtime.tick(1, Map.of("right_hand_empty", 0d, "right_hand_item_id", 7d));
            assertTrue(runtime.snapshot().get("head").visible());
        }
    }

    @Test void remakeUpdateRunsAtRenderRateAndExposesEntityInputs() {
        var bone = new CpmJavascriptRuntime.Bone();
        try (var runtime = new CpmJavascriptRuntime(
                "function update(e,m){var b=m.getBone('head');b.setRotationY(e.getHeadYaw());b.setVisible(e.getLeftHandItem().isEmpty());}",
                Map.of("head", bone))) {
            runtime.update(1.5, Map.of("head_yaw", 13d, "left_hand_empty", 1d));
            var value = runtime.snapshot().get("head");
            assertEquals(13d, value.rotation()[1]);
            assertTrue(value.visible());
        }
    }

    @Test void modernScriptSeesAndMutatesLiveHostBoneAlias() throws Exception {
        var files = new LinkedHashMap<>(remake(""));
        files.put("animation.js", bytes("function update(e,m){m.getBone('head').setRotationX(15);}"));
        var document = (CpmDocument.Remake) reader.read(files, ReadLimits.DEFAULT);
        try (var session = new JavaCpmPoseSession(document)) {
            session.setHostPose(Map.of("head", new CpmPoseSession.BonePose(
                    new double[]{0, 0, 0}, new double[]{Math.toRadians(10), 0, 0},
                    new double[]{1, 1, 1}, true, new double[]{1, 1, 1}, 1, false)));
            var pose = session.tick(0, Map.of()).get("head");
            assertEquals(0, pose.rotation()[0], 1e-9);
            assertEquals(15, pose.rotation()[1], 1e-9);
        }
    }

    @Test void scriptPhysicalizeAndBuiltInEquipmentBonesRemainSafe() {
        var bone=new CpmJavascriptRuntime.Bone();
        try(var runtime=new CpmJavascriptRuntime("function init(e,m){m.getBone('head').physicalize(0,5,.5,0,0);m.getBone('builtin_item_right_right_arm').setScaleX(.75);} function tick(e,m){m.getBone('head').setRotationX(e.getAge());}",Map.of("head",bone))) {
            runtime.init(0);runtime.tick(2);
            assertTrue(runtime.snapshot().containsKey("builtin_item_right_right_arm"));
            assertEquals(2,runtime.snapshot().get("head").rotation()[0]);
            assertTrue(runtime.snapshot().get("head").physicsEnabled());
        }
    }
}
