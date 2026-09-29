package com.elfmcys.ysm.client.compat.touhoulittlemaid.util;

import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.geo.animated.GeoModelState;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocator;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.core.processor.ILocationBone;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.ILocationModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Copies the extracted frame's locator hierarchies; TLM never borrows mutable animation arrays. */
public final class TlmConverterHelper {
    public static final ILocationModel EMPTY = new ILocationModel() {};
    private TlmConverterHelper() {}

    public static ILocationModel snapshot(AnimatedGeoModel animated, GeoModelState state) {
        if (animated == null || !state.isValid()) return EMPTY;
        var model = animated.getModel();
        var locators = PlayerLocator.get();
        if (model.locatorType() != locators) return EMPTY;
        var attributes = animated.getBoneAttributes().clone();
        var bones = model.sortedBones();
        var baked = model.bakedModel().runtimeModel();
        var groups = new HashMap<GeoLocator, List<List<? extends ILocationBone>>>();
        for (int index : state.getNativeState().runtimeState().locators()) {
            var locator = bones.get(index).locatorType();
            if (locator == null) continue;
            var chain = new ArrayList<ILocationBone>();
            for (int i = index; i >= 0; i = baked.bones().get(i).parent()) {
                var pivot = bones.get(i).pivot();
                int offset = i * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;
                chain.add(new Bone(attributes[offset], attributes[offset+1], attributes[offset+2],
                        attributes[offset+3], attributes[offset+4], attributes[offset+5],
                        attributes[offset+6], attributes[offset+7], attributes[offset+8],
                        pivot.x, pivot.y, pivot.z));
            }
            Collections.reverse(chain);
            groups.computeIfAbsent(locator, key -> new ArrayList<>()).add(List.copyOf(chain));
        }
        groups.replaceAll((key, value) -> List.copyOf(value));
        boolean hasBackpack = !model.locatorMap().get(Byte.toUnsignedInt(locators.backpack.seq()) - 1).isEmpty();
        return new Locations(Map.copyOf(groups), hasBackpack);
    }

    private record Locations(Map<GeoLocator, List<List<? extends ILocationBone>>> groups,
                             boolean hasBackpack) implements ILocationModel {
        private List<ILocationBone> first(GeoLocator locator) {
            var chains = groups.getOrDefault(locator, List.of());
            return chains.isEmpty() ? List.of() : List.copyOf(chains.get(0));
        }
        private List<List<? extends ILocationBone>> extra(GeoLocator locator) {
            var chains = groups.getOrDefault(locator, List.of());
            return chains.size() < 2 ? List.of() : chains.subList(1, chains.size());
        }
        @Override public List<ILocationBone> leftHandBones() { return first(PlayerLocator.get().leftHand); }
        @Override public List<ILocationBone> rightHandBones() { return first(PlayerLocator.get().rightHand); }
        @Override public List<ILocationBone> leftWaistBones() { return first(PlayerLocator.get().leftWaist); }
        @Override public List<ILocationBone> rightWaistBones() { return first(PlayerLocator.get().rightWaist); }
        @Override public List<ILocationBone> tacPistolBones() { return first(PlayerLocator.get().pistol); }
        @Override public List<ILocationBone> tacRifleBones() { return first(PlayerLocator.get().rifle); }
        @Override public List<ILocationBone> headBones() { return first(PlayerLocator.get().head); }
        @Override public List<List<? extends ILocationBone>> extraLeftHandBones() { return extra(PlayerLocator.get().leftHand); }
        @Override public List<List<? extends ILocationBone>> extraRightHandBones() { return extra(PlayerLocator.get().rightHand); }
        @Override public List<ILocationBone> backpackBones() {
            var locators = PlayerLocator.get();
            return first(hasBackpack ? locators.backpack : locators.elytra);
        }
    }

    private record Bone(float rx, float ry, float rz, float x, float y, float z,
                        float sx, float sy, float sz, float px, float py, float pz) implements ILocationBone {
        @Override public float getRotationX() { return rx; }
        @Override public float getRotationY() { return ry; }
        @Override public float getRotationZ() { return rz; }
        @Override public float getPositionX() { return x; }
        @Override public float getPositionY() { return y; }
        @Override public float getPositionZ() { return z; }
        @Override public float getScaleX() { return sx; }
        @Override public float getScaleY() { return sy; }
        @Override public float getScaleZ() { return sz; }
        @Override public float getPivotX() { return px; }
        @Override public float getPivotY() { return py; }
        @Override public float getPivotZ() { return pz; }
    }
}
