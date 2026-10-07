package com.elfmcys.ysm.client.controller;

import com.elfmcys.ysm.client.entity.CustomEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.PlayerModelResources;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import net.minecraft.inventory.EntityEquipmentSlot;
import org.apache.commons.lang3.function.TriFunction;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;

public class ArmorControllerDiscovery<T extends CustomEntity<?>> implements ControllerDiscovery<T, PlayerModelResources> {
    private final String category;
    private final String name;
    private final ResourceAdapter<PlayerModelResources> resourceAdapter;
    private final TriFunction<String, T, EntityEquipmentSlot, IAnimationController<T>> controllerFunc;

    public ArmorControllerDiscovery(String category, String name, ResourceAdapter<PlayerModelResources> resourceAdapter, TriFunction<String, T, EntityEquipmentSlot, IAnimationController<T>> controllerFunc) {
        this.category = category;
        this.name = name;
        this.resourceAdapter = resourceAdapter;
        this.controllerFunc = controllerFunc;
    }

    @Override
    public ControllerFactory<T> process(PlayerModelResources playerResources, CommonAsset assets) {
        List<Pair<String, EntityEquipmentSlot>> slots = new ArrayList<>();
        var condition = resourceAdapter.getArmorCondition(playerResources, assets);
        var controllers = resourceAdapter.getControllers(playerResources, assets);
        var animations = resourceAdapter.getAnimations(playerResources, assets);
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            var controllerName = String.format("%s.%s_%s", category, name, slot.getName());
            if (controllers.containsKey(controllerName)) {
                slots.add(Pair.of(controllerName, slot));
                continue;
            }
            if (assets.eventHandlers().containsKey(String.format("%s_ctrl_%s_%s", category, name, slot.getName()))) {
                slots.add(Pair.of(controllerName, slot));
                continue;
            }
            if (slot.getSlotType() == EntityEquipmentSlot.Type.ARMOR && (condition.hasTest(slot) || animations.containsKey(slot.getName() + ":default"))) {
                slots.add(Pair.of(controllerName, slot));
                continue;
            }
        }
        return (animatable, consumer) -> {
            if (!(animatable instanceof IPreviewEntity)) {
                for (var slot : slots) {
                    consumer.accept(controllerFunc.apply(slot.getLeft(), animatable, slot.getRight()));
                }
            }
        };
    }
}
