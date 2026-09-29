package cc.sirrus.ysmlib.render.java;

import cc.sirrus.ysmlib.render.*;
import cc.sirrus.ysmlib.render.Geometry.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LocatorVisibilityTest {
    @Test void selectsEachArmAndDescendantsWithoutLosingAncestorTransformsOrChangingAnimation() {
        var model = model();
        var attributes = attributes();
        var saved = attributes.clone();
        try (var state = new JavaModelState()) {
            assertTrue(state.extract(model, LocatorVisibility.select(model, attributes, 1)));
            assertEquals(List.of(1, 2), state.renderBones());
            assertEquals(8, state.vertices());
            assertEquals(1f, state.pose(2, new Matrix4f()).m31());
            assertTrue(state.extract(model, LocatorVisibility.select(model, attributes, 2)));
            assertEquals(List.of(3), state.renderBones());
            assertEquals(4, state.vertices());
            assertTrue(state.extract(model, LocatorVisibility.select(model, attributes, 3)));
            assertTrue(state.renderBones().isEmpty());
        }
        assertArrayEquals(saved, attributes);
    }

    @Test void animationHidingStillAppliesToSelectedArm() {
        var model = model();
        var attributes = attributes();
        attributes[14 + 9] = 1;
        attributes[14 + 10] = 1;
        try (var state = new JavaModelState()) {
            assertTrue(state.extract(model, LocatorVisibility.select(model, attributes, 1)));
            assertTrue(state.renderBones().isEmpty());
        }
    }

    private static float[] attributes() {
        var a = new float[4 * 14];
        for (int b = 0; b < 4; b++) {
            int o = b * 14;
            a[o + 6] = a[o + 7] = a[o + 8] = 1;
            a[o + 12] = 0xffffff;
            a[o + 13] = 0xffff;
        }
        a[4] = 16;
        a[14 + 11] = 1;
        a[42 + 11] = 2;
        return a;
    }

    private static BakedModel model() {
        var quad = new BakedModel.Quad(List.of(0,1,2,3), List.of(new V2(0,0),new V2(1,0),new V2(1,1),new V2(0,1)),
                new V3(0,0,1),new V4(1,0,0,1),new V3(0,0,0),0,1);
        var cube = new BakedModel.Cube(List.of(new V3(0,0,0),new V3(1,0,0),new V3(1,1,0),new V3(0,1,0)),List.of(quad),1);
        List<List<BakedModel.Cube>> parts = List.of(List.of(),List.of(cube),List.of(),List.of());
        return new BakedModel(List.of(
                new BakedModel.Bone(0,-1,4,0,new V3(0,0,0),true,parts),
                new BakedModel.Bone(1,0,3,1,new V3(0,0,0),true,parts),
                new BakedModel.Bone(2,1,3,2,new V3(0,0,0),true,parts),
                new BakedModel.Bone(3,0,4,1,new V3(0,0,0),true,parts)),false);
    }
}
