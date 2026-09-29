package com.elfmcys.ysm.natives.render;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.proto.mixel.asset.model.data.Bone;
import com.elfmcys.ysm.proto.mixel.asset.model.data.CubeLegacy;
import com.elfmcys.ysm.proto.mixel.asset.model.data.Cubes;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModel;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoProperties;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import com.elfmcys.ysm.util.ProtoUtil;
import java.nio.ByteBuffer;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(NativeLibraryExtension.class)
class NativeModelStateIntegrationTest {
    @Test
    void tryBakeRejectsMalformedJavaOwnedGeometry() {
        try (var modelData = ArrayBuffer.move(new byte[]{(byte) 0xff});
             var texture = NativeBuffer.allocate(4)) {
            assertFalse(NativeBakedModel.tryBake(
                    modelData, texture, 1, 1, 0, false, false, false));
        }
    }

    @Test
    void extractsQuadUv() throws Exception {
        var cube = CubeLegacy.newBuilder()
                .setFaceCount(1)
                .addPos(0).addPos(0).addPos(0).addPos(1).addPos(0).addPos(0)
                .addPos(1).addPos(1).addPos(0).addPos(0).addPos(1).addPos(0)
                .addPosIndices(0).addPosIndices(1).addPosIndices(2).addPosIndices(3)
                .addUv(0).addUv(0).addUv(0.5f).addUv(0)
                .addUv(0.5f).addUv(0.5f).addUv(0).addUv(0.5f)
                .addUvIndices(0).addUvIndices(1).addUvIndices(2).addUvIndices(3)
                .addNormal(0).addNormal(0).addNormal(1)
                .build();
        var cubes = Cubes.newBuilder().addCubesLegacy(cube).build();
        var model = GeoModel.newBuilder()
                .addBones(Bone.newBuilder()
                        .setName("root")
                        .addPivot(0).addPivot(0).addPivot(0)
                        .addRotate(0).addRotate(0).addRotate(0)
                        .setCubeCount(1).build())
                .setProperties(properties())
                .setCubes(ByteBuffer.wrap(ProtoUtil.serializeToArray(cubes)))
                .build();

        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize());
             var texture = NativeBuffer.allocate(16)) {
            model.writeTo(ProtoUtil.sink(modelData));
            texture.nio().putLong(0, -1L).putLong(8, -1L);
            var baked = NativeBakedModel.bake(modelData, 1, texture,
                    2, 2, 29, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, 1);
                try (var bakedModel = read.bakedModel()) {
                    var uv = bakedModel.getCubeData(0, 1, 0, 1)[0]
                            .quads()[0].uv();
                    assertArrayEquals(new Vector2f[]{
                            new Vector2f(0, 0), new Vector2f(0.5f, 0),
                            new Vector2f(0.5f, 0.5f), new Vector2f(0, 0.5f)
                    }, uv);
                }
            }
        }
    }

    @Test
    void extractsBorrowedViewsAndCountsWithNoRenderBones() throws Exception {
        var model = GeoModel.newBuilder()
                .addBones(Bone.newBuilder()
                        .setName("root")
                        .addRotate(0).addRotate(0).addRotate(0)
                        .addPivot(0).addPivot(0).addPivot(0).build())
                .setProperties(properties())
                .setCubes(ByteBuffer.allocate(0))
                .build();

        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize());
             var texture = NativeBuffer.allocate(4)) {
            model.writeTo(ProtoUtil.sink(modelData));
            var baked = NativeBakedModel.bake(modelData, 1, texture,
                    1, 1, 0, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, 1);
                try (var bakedModel = read.bakedModel();
                     var state = NativeModelState.create()) {
                    var attributes = new float[AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
                    attributes[6] = 1;
                    attributes[7] = 1;
                    attributes[8] = 1;
                    attributes[13] = 0xFFFF;

                    assertTrue(state.extract(bakedModel, attributes));
                    assertEquals(1, state.getBonePoses().getBoneCount());
                    assertEquals(0, state.getRenderBoneIndices().capacity());
                    assertTrue(state.getLocatorBoneIndices().isEmpty());
                    assertEquals(0, state.getTotalVertexCount());
                    assertEquals(0, state.getTranslucentVertexCount());

                    state.close();
                    assertFalse(state.isValid());
                    assertNull(state.getBonePoses());
                    assertNull(state.getRenderBoneIndices());
                    assertThrows(IllegalStateException.class, () -> state.runtimeState().model());
                }
            }
        }
    }

    private static GeoProperties properties() {
        return GeoProperties.newBuilder()
                .setTextureHeight(0)
                .setTextureWidth(0)
                .build();
    }
}
