package com.elfmcys.ysm.geckolib3.geo.animated;

import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocator;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.elfmcys.ysm.util.Closeable;
import com.elfmcys.ysm.util.ExposedShortArrayList;
import com.elfmcys.ysm.geckolib3.util.PoseStack;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.minecraft.client.renderer.GlStateManager;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.function.Consumer;


public final class GeoModelState implements Closeable {
    private final NativeModelState nativeState = NativeModelState.create();
    private final ReferenceArrayList<ShortArrayList> activeLocatorMap = new ReferenceArrayList<>(0);

    private GeoModel model;

    public boolean extract(AnimatedGeoModel animatedModel) {
        invalidate();
        var model = animatedModel.getModel();
        var boneCount = model.sortedBones().size();
        var activeLocatorMap = this.activeLocatorMap;

        var valid = nativeState.extract(model.bakedModel(),
                animatedModel.getBoneAttributes());
        if (!valid) {
            return false;
        }

        var locatorSlotCount = model.locatorType().size();
        if (activeLocatorMap.size() != locatorSlotCount) {
            var oldSize = activeLocatorMap.size();
            activeLocatorMap.size(locatorSlotCount);
            for (var i = oldSize; i < locatorSlotCount; i++) {
                activeLocatorMap.set(i, new ExposedShortArrayList(2));
            }
        }
        for(var list : activeLocatorMap) {
            list.clear();
        }

        var sortedBones = model.sortedBones();
        var activeLocators = nativeState.getLocatorBoneIndices();
        for (var i = 0; i < activeLocators.size(); i++) {
            var boneIndex = Short.toUnsignedInt(activeLocators.getShort(i));
            if (boneIndex >= boneCount) {
                return false;
            }
            var locatorType = sortedBones.get(boneIndex).locatorType();
            if (locatorType != null) {
                activeLocatorMap.get(Byte.toUnsignedInt(locatorType.seq()) - 1).add((short) boneIndex);
            }
        }

        this.model = model;
        return true;
    }

    public boolean isValid() {
        return nativeState.isValid();
    }

    public int getVertexCount() {
        return nativeState.getTotalVertexCount();
    }

    public boolean hasTranslucentVertices() {
        return nativeState.getTranslucentVertexCount() != 0;
    }

    public NativeModelState getNativeState() {
        return nativeState;
    }

    private static final FloatBuffer LOCATOR_MAT_SCRATCH = ByteBuffer.allocateDirect(16 * Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    public void visitLocatorGroupOrDefault(GeoLocator locator, Runnable visitor,
                                           Runnable applyDefaultPose) {
        if (locatorGroupSize(locator) > 0) {
            visitLocatorGroup(locator, visitor);
        } else {
            GlStateManager.pushMatrix();
            try {
                applyDefaultPose.run();
                visitor.run();
            } finally {
                GlStateManager.popMatrix();
            }
        }
    }

    public void visitLocatorGroup(GeoLocator locator, Runnable visitor) {
        if (model.locatorType() != locator.type()) {
            throw new IllegalArgumentException("locator type mismatch");
        }

        var bonePose = new Matrix4f();
        var bonePoseView = nativeState.getBonePoses();
        for (var boneIndexShort : activeLocatorMap.get(Byte.toUnsignedInt(locator.seq()) - 1)) {
            var boneIndex = Short.toUnsignedInt(boneIndexShort);
            var bone = model.sortedBones().get(boneIndex);
            bonePoseView.getPose(boneIndex, bonePose);
            var pivot = bone.pivot();
            GlStateManager.pushMatrix();
            try {
                GlStateManager.multMatrix(bonePose.get(0, LOCATOR_MAT_SCRATCH));
                GlStateManager.translate(pivot.x / 16, pivot.y / 16, pivot.z / 16);
                visitor.run();
            } finally {
                GlStateManager.popMatrix();
            }
        }
    }

    public int locatorGroupSize(GeoLocator locator) {
        if (model.locatorType() != locator.type()) {
            throw new IllegalArgumentException("locator type mismatch");
        }
        return activeLocatorMap.get(Byte.toUnsignedInt(locator.seq()) - 1).size();
    }

    private void invalidate() {
        model = null;
    }

    public GeoModel getModel() {
        return model;
    }

    @Override
    public void close() {
        invalidate();
        nativeState.close();
    }
}
