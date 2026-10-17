package com.elfmcys.ysm.format.parser;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.cpm.CpmDocument;
import cc.sirrus.ysmlib.cpm.CpmValue;
import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.CompatibilityReport;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import us.hebi.quickbuf.ProtoSource;
import com.elfmcys.ysm.proto.mixel.asset.model.data.Cubes;
import static org.junit.jupiter.api.Assertions.*;

class CpmYsmModelAdapterTest {
    @Test void legacyBoxesEnterExistingYsmGeoBuilder() throws Exception {
        var json = "{\"modelId\":\"x\",\"modelName\":\"X\",\"bones\":[{\"id\":\"root\",\"boxes\":[{\"coordinates\":[-1,2,3,4,5,6],\"textureOffset\":[0,0]}]}]}";
        var source = YsmRuntime.cpm().read(Map.of("model.json", new ByteData(json.getBytes(StandardCharsets.UTF_8))), ReadLimits.DEFAULT);
        var built = CpmYsmModelAdapter.build(source);
        assertTrue(built.model.bones().stream().anyMatch(b -> b.name().equals("root")));
        assertTrue(built.model.cubes().remaining() > 0);
        assertEquals(1, Cubes.parseFrom(ProtoSource.newInstance(built.model.cubes())).cubesLegacy().size());
    }

    @Test void legacyFirstPersonListMarksEveryIndependentRootWithoutReparenting() throws Exception {
        var fields = Map.<String, CpmValue>of("fpLeft", new CpmValue.ArrayValue(List.of(
                new CpmValue.StringValue("arm"), new CpmValue.StringValue("decoration"))));
        var sourceValue = new CpmValue.ObjectValue(fields);
        var metadata = new CpmDocument.Metadata("x", "X", "", List.of(), "", "");
        var empty = new CompatibilityReport(List.of(), List.of());
        var legacy = new CpmDocument.Legacy(metadata, sourceValue, List.of(
                new CpmDocument.LegacyBone("body", "none", -1, new CpmValue.ObjectValue(Map.of()), List.of(), List.of()),
                new CpmDocument.LegacyBone("arm", "body", 0, new CpmValue.ObjectValue(Map.of()), List.of(), List.of()),
                new CpmDocument.LegacyBone("decoration", "body", 0, new CpmValue.ObjectValue(Map.of()), List.of(), List.of()),
                new CpmDocument.LegacyBone("other", "body", 0, new CpmValue.ObjectValue(Map.of()), List.of(), List.of())
        ), Map.of(), empty);
        var source = legacy;
        var built = CpmYsmModelAdapter.build(source);
        var bones = built.model.bones();
        var arm = bones.stream().filter(b -> b.name().equals("arm")).findFirst().orElseThrow();
        var decoration = bones.stream().filter(b -> b.name().equals("decoration")).findFirst().orElseThrow();
        var other = bones.stream().filter(b -> b.name().equals("other")).findFirst().orElseThrow();
        assertEquals("body", arm.parent().get());
        assertEquals("LeftArm", arm.locator().get());
        assertEquals("body", decoration.parent().get());
        assertEquals("LeftArm", decoration.locator().get());
        assertTrue(other.locator().isEmpty());
    }

    @Test void legacyCustomBoneShadowingVanillaHostKeepsAcyclicHierarchy() throws Exception {
        var json = "{\"modelId\":\"x\",\"modelName\":\"X\",\"bones\":["
                + "{\"id\":\"neck\"},{\"id\":\"head\",\"parent\":\"neck\"},"
                + "{\"id\":\"head_c\",\"parent\":\"head\",\"boxes\":[{\"coordinates\":[0,0,0,1,1,1],\"textureOffset\":[0,0]}]}]}";
        var source = YsmRuntime.cpm().read(Map.of("model.json", new ByteData(json.getBytes(StandardCharsets.UTF_8))), ReadLimits.DEFAULT);
        var built = CpmYsmModelAdapter.build(source);
        var names = built.model.bones().stream().map(b -> b.name()).toList();
        assertTrue(names.contains("__cpm_head"));
        assertTrue(names.contains("head_c"));
        assertEquals("Head", built.model.bones().stream().filter(b -> b.name().equals("head_c")).findFirst().orElseThrow().parent().orElseThrow());
    }

    @Test void legacyHostParentsAreMaterializedForTheBakeHierarchy() throws Exception {
        var json = "{\"modelId\":\"x\",\"modelName\":\"X\",\"bones\":["
                + "{\"id\":\"head_c\",\"parent\":\"head\",\"attached\":[\"head_wearing\"],\"boxes\":[{\"coordinates\":[-2,0,2,4,4,4],\"textureOffset\":[0,0]}]}]}";
        var source = YsmRuntime.cpm().read(Map.of("model.json", new ByteData(json.getBytes(StandardCharsets.UTF_8))), ReadLimits.DEFAULT);
        var built = CpmYsmModelAdapter.build(source);
        assertTrue(built.model.bones().stream().anyMatch(b -> b.name().equals("Head")));
        var custom = built.model.bones().stream().filter(b -> b.name().equals("head_c")).findFirst().orElseThrow();
        assertEquals("Head", custom.parent().orElseThrow());
        assertTrue(custom.locator().isEmpty());
    }

    @Test void legacyPositionsUseCustomModelRendererSpaceBeforePivotBaking() throws Exception {
        var json = "{\"modelId\":\"x\",\"modelName\":\"X\",\"bones\":["
                + "{\"id\":\"piece\",\"parent\":\"head\",\"position\":[1,2,3],"
                + "\"boxes\":[{\"coordinates\":[0,0,0,1,1,1],\"textureOffset\":[0,0]}]}]}";
        var source = YsmRuntime.cpm().read(Map.of("model.json",
                new ByteData(json.getBytes(StandardCharsets.UTF_8))), ReadLimits.DEFAULT);
        var built = CpmYsmModelAdapter.build(source);
        var piece = built.model().bones().stream().filter(b -> b.name().equals("piece"))
                .findFirst().orElseThrow();
        assertEquals("Head", piece.parent().orElseThrow());
        // Legacy custom bones use the local-transform branch; authored
        // position stays in the pose channel and the baked pivot is zero.
        assertEquals(0f, piece.pivot().getFloat(0), 0.0001f);
        assertEquals(0f, piece.pivot().getFloat(1), 0.0001f);
        assertEquals(0f, piece.pivot().getFloat(2), 0.0001f);
    }

    @Test void remakeEntitySizeDoesNotBecomeAnisotropicRenderScale() throws Exception {
        var root = "{\"name\":\"X\",\"skeleton\":{\"type\":\"biped\"},"
                + "\"size\":{\"standing\":[0.45,1.45]},\"parts\":[{\"name\":\"body\",\"texture\":\"body\"}]}";
        var geometry = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"body\",\"texture_width\":64,\"texture_height\":64},\"bones\":[{\"name\":\"body\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[-1,0,-1],\"size\":[2,2,2],\"uv\":[0,0]}]}]}]}";
        var source = YsmRuntime.cpm().read(Map.of("model.json", new ByteData(root.getBytes(StandardCharsets.UTF_8)),
                "body.json", new ByteData(geometry.getBytes(StandardCharsets.UTF_8))), ReadLimits.DEFAULT);
        var built = CpmYsmModelAdapter.build(source);
        assertEquals(1f, built.heightScale(), 0.0001f);
        assertEquals(1f, built.widthScale(), 0.0001f);
    }

    @Test void remakeSkeletonParametersDriveCanonicalHostAttachmentSemantics() throws Exception {
        var root = "{\"name\":\"X\",\"skeleton\":{\"type\":\"biped\",\"param\":{\"head_pivot_height\":18,\"body_pivot_height\":18,\"arm_pivot_height\":17.5,\"arm_interval\":6,\"leg_length\":9,\"leg_interval\":4}},\"parts\":[{\"name\":\"body\",\"texture\":\"body\"}]}";
        var geometry = "{\"format_version\":\"1.10.0\",\"geometry.model\":{\"texturewidth\":64,\"textureheight\":64,\"bones\":[{\"name\":\"head_c\",\"pivot\":[0,18,0],\"cubes\":[]}]}}";
        var source = YsmRuntime.cpm().read(Map.of("model.json", new ByteData(root.getBytes(StandardCharsets.UTF_8)),
                "body.json", new ByteData(geometry.getBytes(StandardCharsets.UTF_8))), ReadLimits.DEFAULT);
        var built = CpmYsmModelAdapter.build(source);
        var head = built.model().bones().stream().filter(b -> b.name().equals("Head")).findFirst().orElseThrow();
        var custom = built.model().bones().stream().filter(b -> b.name().equals("head_c")).findFirst().orElseThrow();
        assertEquals(0f, head.pivot().getFloat(1), 0.0001f);
        assertEquals("Head", custom.parent().orElseThrow());
        assertEquals("Head", custom.locator().orElseThrow());
    }

    @Test void remakeRotationMatchesCustomPlayerModelQuaternionAxes() throws Exception {
        var root = "{\"name\":\"X\",\"skeleton\":{\"type\":\"biped\"},\"parts\":[{\"name\":\"body\",\"texture\":\"body\"}]}";
        var geometry = "{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"body\",\"texture_width\":64,\"texture_height\":64},\"bones\":[{\"name\":\"custom\",\"pivot\":[0,24,0],\"rotation\":[10,20,30],\"cubes\":[]}]}]}";
        var source = YsmRuntime.cpm().read(Map.of("model.json", new ByteData(root.getBytes(StandardCharsets.UTF_8)),
                "body.json", new ByteData(geometry.getBytes(StandardCharsets.UTF_8))), ReadLimits.DEFAULT);
        var built = CpmYsmModelAdapter.build(source);
        var bone = built.model().bones().stream().filter(b -> b.name().equals("custom")).findFirst().orElseThrow();
        // Modern authored rest rotation is consumed by the complete pose
        // channel; the baked local bone has zero static rotation.
        assertEquals(0f, bone.rotate().getFloat(0), 1e-6);
        assertEquals(0f, bone.rotate().getFloat(1), 1e-6);
        assertEquals(0f, bone.rotate().getFloat(2), 1e-6);
        assertEquals(2, built.model().properties().transformMode());
    }

    @Test void remakeGeometryUsesCustomPlayerModelYOriginAndLocalBoneOrigin() throws Exception {
        var root = "{\"name\":\"X\",\"skeleton\":{\"type\":\"biped\",\"param\":{\"head_pivot_height\":18}},\"parts\":[{\"name\":\"body\",\"texture\":\"body\"}]}";
        var geometry = "{\"format_version\":\"1.10.0\",\"geometry.model\":{\"texturewidth\":64,\"textureheight\":64,\"bones\":[{\"name\":\"head_c\",\"pivot\":[0,18,0],\"cubes\":[{\"origin\":[-4,18,-4],\"size\":[8,8,8],\"uv\":[0,0]}]}]}}";
        var source = YsmRuntime.cpm().read(Map.of("model.json", new ByteData(root.getBytes(StandardCharsets.UTF_8)),
                "body.json", new ByteData(geometry.getBytes(StandardCharsets.UTF_8))), ReadLimits.DEFAULT);
        var built = CpmYsmModelAdapter.build(source);
        var head = built.model().bones().stream().filter(b -> b.name().equals("head_c")).findFirst().orElseThrow();
        assertEquals(0f, head.pivot().getFloat(1), 0.0001f);
        var cubes = Cubes.parseFrom(ProtoSource.newInstance(built.model().cubes()));
        var encoded = cubes.cubesLegacy().stream().filter(c -> c.pos().size() > 0).findFirst().orElseThrow();
        var y = encoded.pos().toFloatArray();
        var min = Float.POSITIVE_INFINITY; var max = Float.NEGATIVE_INFINITY;
        for (int i = 1; i < y.length; i += 3) { min = Math.min(min, y[i]); max = Math.max(max, y[i]); }
        // The Modern adapter preserves the ModelRenderer-local interval:
        // [bonePivot.y-origin.y-size.y, bonePivot.y-origin.y].
        assertEquals(-8f / 16f, min, 0.0001f);
        assertEquals(0f, max, 0.0001f);
    }

    @Test void legacyConversionMarksItsDedicatedTransformMode() throws Exception {
        var json = "{\"modelId\":\"x\",\"modelName\":\"X\",\"bones\":[]}";
        var source = YsmRuntime.cpm().read(Map.of("model.json", new ByteData(json.getBytes(StandardCharsets.UTF_8))), ReadLimits.DEFAULT);
        assertEquals(1, CpmYsmModelAdapter.build(source).model().properties().transformMode());
    }

    @Test void legacyUsesThe112ModelPlayerRotationPointsForHostBones() throws Exception {
        var json = "{\"modelId\":\"x\",\"modelName\":\"X\",\"bones\":[]}";
        var source = YsmRuntime.cpm().read(Map.of("model.json",
                new ByteData(json.getBytes(StandardCharsets.UTF_8))), ReadLimits.DEFAULT);
        var model = CpmYsmModelAdapter.build(source).model();
        var leftArm = model.bones().stream().filter(b -> b.name().equals("LeftArm")).findFirst().orElseThrow();
        var rightArm = model.bones().stream().filter(b -> b.name().equals("RightArm")).findFirst().orElseThrow();
        var leftLeg = model.bones().stream().filter(b -> b.name().equals("LeftLeg")).findFirst().orElseThrow();
        var rightLeg = model.bones().stream().filter(b -> b.name().equals("RightLeg")).findFirst().orElseThrow();
        // Vanilla 1.12 ModelPlayer points are arms (+/-5,2,0) and legs
        // (+/-1.9,12,0).  The converted YSM frame adds the 24 pixel model
        // origin while preserving the final rendered X sign.
        assertEquals(5f, leftArm.pivot().getFloat(0), 0.0001f);
        assertEquals(26f, leftArm.pivot().getFloat(1), 0.0001f);
        assertEquals(-5f, rightArm.pivot().getFloat(0), 0.0001f);
        assertEquals(26f, rightArm.pivot().getFloat(1), 0.0001f);
        assertEquals(1.9f, leftLeg.pivot().getFloat(0), 0.0001f);
        assertEquals(36f, leftLeg.pivot().getFloat(1), 0.0001f);
        assertEquals(-1.9f, rightLeg.pivot().getFloat(0), 0.0001f);
        assertEquals(36f, rightLeg.pivot().getFloat(1), 0.0001f);
        // CustomModel's PlayerBone.OriginalBone.getParent() is null for every
        // host slot.  The canonical Legacy graph must therefore not add the
        // Modern/YSM synthetic Arm/Leg grouping chain.
        assertTrue(model.bones().stream().noneMatch(b -> b.name().equals("Arm")));
        assertTrue(model.bones().stream().noneMatch(b -> b.name().equals("Leg")));
        assertTrue(leftArm.parent().isEmpty());
        assertTrue(rightArm.parent().isEmpty());
    }

    @Test void legacyBoxConvertsTheCustomModelMaximumZConvention() throws Exception {
        var json = "{\"modelId\":\"x\",\"modelName\":\"X\",\"bones\":["
                + "{\"id\":\"piece\",\"boxes\":[{\"coordinates\":[0,0,4,2,3,6],\"textureOffset\":[0,0]}]}]}";
        var source = YsmRuntime.cpm().read(Map.of("model.json",
                new ByteData(json.getBytes(StandardCharsets.UTF_8))), ReadLimits.DEFAULT);
        var cubes = Cubes.parseFrom(ProtoSource.newInstance(CpmYsmModelAdapter.build(source).model().cubes()));
        var positions = cubes.cubesLegacy().stream().findFirst().orElseThrow().pos().toFloatArray();
        var min = Float.POSITIVE_INFINITY; var max = Float.NEGATIVE_INFINITY;
        for (int i = 2; i < positions.length; i += 3) {
            min = Math.min(min, positions[i]); max = Math.max(max, positions[i]);
        }
        assertEquals((4f - 6f) / 16f, min, 0.0001f);
        assertEquals(4f / 16f, max, 0.0001f);
    }
}
