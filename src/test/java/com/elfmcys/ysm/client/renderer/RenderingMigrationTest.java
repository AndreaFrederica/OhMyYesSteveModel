package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.client.model.locator.VehicleLocator;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.util.TlmConverterHelper;
import com.elfmcys.ysm.geckolib3.geo.animated.GeoModelState;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.natives.render.NativeBakedModel;
import com.elfmcys.ysm.proto.mixel.asset.model.data.Bone;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoProperties;
import com.elfmcys.ysm.util.ProtoUtil;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.util.RenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.eventbus.api.BusBuilder;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class RenderingMigrationTest {
    @BeforeAll static void setup() throws Exception {
        System.setProperty("ysm.runtime.javaOnly", "true");
        var bus = YesSteveModel.class.getDeclaredField("EVENT_BUS");
        bus.setAccessible(true);
        if (bus.get(null) == null) bus.set(null, BusBuilder.builder().build());
        PlayerLocator.init();
        VehicleLocator.init();
    }

    @Test void maidSnapshotMatchesRuntimePoseAndSurvivesAnimationMutationAndClose() throws Exception {
        try (var model = model(PlayerLocator.get(), "LeftHandLocator", "LeftHandLocator2", "ElytraLocator");
             var state = new GeoModelState()) {
            var animated = new AnimatedGeoModel(model);
            var root = animated.getSortedBones().get(0);
            root.setPosition(2, 8, -3);
            root.setRotation(0.2f, -0.1f, 0.3f);
            assertTrue(state.extract(animated));
            var locations = TlmConverterHelper.snapshot(animated, state);
            assertEquals(2, locations.leftHandBones().size());
            assertEquals(1, locations.extraLeftHandBones().size());
            assertFalse(locations.backpackBones().isEmpty());
            var nativePose = new PoseStack();
            assertTrue(state.applyLocatorPose(PlayerLocator.get().leftHand, 0, nativePose));
            var tlmPose = new PoseStack();
            RenderUtils.prepMatrixForLocator(tlmPose, locations.leftHandBones());
            assertTrue(nativePose.last().pose().equals(tlmPose.last().pose(), 0.00001f),
                    "TLM attachment must agree with the rendered model's pose");
            root.setPosition(0, 1000, 0);
            assertEquals(8, locations.leftHandBones().get(0).getPositionY());
            state.close();
            assertEquals(8, locations.leftHandBones().get(0).getPositionY());
        }
    }

    @Test void hiddenBackpackDoesNotFallBackToVisibleElytra() throws Exception {
        try (var model = model(PlayerLocator.get(), "BackpackLocator", "ElytraLocator");
             var state = new GeoModelState()) {
            var animated = new AnimatedGeoModel(model);
            var backpack = animated.getSortedBones().get(1);
            backpack.setHidden(true);
            assertTrue(state.extract(animated));
            assertTrue(TlmConverterHelper.snapshot(animated, state).backpackBones().isEmpty());
            backpack.setHidden(false);
            backpack.setScale(0, 0, 0);
            assertTrue(state.extract(animated));
            assertTrue(TlmConverterHelper.snapshot(animated, state).backpackBones().isEmpty());
        }
    }

    @Test void hiddenSeatDoesNotShiftTheNextPassengerAndMissingSeatLeavesPoseUntouched() throws Exception {
        try (var model = model(VehicleLocator.get(), "PassengerLocator", "PassengerLocator2", "PassengerLocator3");
             var state = new GeoModelState()) {
            var animated = new AnimatedGeoModel(model);
            animated.getSortedBones().get(1).setHidden(true);
            assertTrue(state.extract(animated));
            var pose = new PoseStack();
            pose.translate(9, 3, 2);
            var before = new Matrix4f(pose.last().pose());
            assertFalse(state.applyLocatorPose(VehicleLocator.get().passenger, 0, pose));
            assertFalse(state.applyLocatorPose(VehicleLocator.get().passenger, 3, pose));
            assertEquals(before, pose.last().pose());
            assertTrue(state.applyLocatorPose(VehicleLocator.get().passenger, 1, pose));
            assertEquals(5, pose.last().pose().m31(), 0.00001);
        }
    }

    private static GeoModel model(GeoLocatorType locators, String... names) throws Exception {
        var builder = com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModel.newBuilder()
                .setProperties(GeoProperties.newBuilder().setTextureWidth(1).setTextureHeight(1).build())
                .setCubes(ByteBuffer.allocate(0))
                .addBones(bone("root", null, 0));
        for (int i = 0; i < names.length; i++) builder.addBones(bone(names[i], "root", (i + 1) * 16));
        var raw = builder.build();
        try (var bytes = ArrayBuffer.allocate(raw.getSerializedSize()); var texture = NativeBuffer.allocate(4)) {
            raw.writeTo(ProtoUtil.sink(bytes));
            texture.nio().putInt(0, -1);
            var baked = NativeBakedModel.bake(bytes, names.length + 1, texture, 1, 1, 0, false);
            try (var payload = baked.bakedData()) {
                return new GeoModel("render-migration-test", raw, locators, NativeBakedModel.read(payload, names.length + 1));
            }
        }
    }

    private static Bone bone(String name, String parent, float y) {
        var builder = Bone.newBuilder().setName(name).addPivot(0).addPivot(y).addPivot(0)
                .addRotate(0).addRotate(0).addRotate(0);
        if (parent != null) builder.setParent(parent);
        return builder.build();
    }
}
