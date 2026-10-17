package com.elfmcys.ysm.client.animation;

import cc.sirrus.ysmlib.scene.SceneModelFeatures;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Class/use-action categories supplement registry IDs and the stack's actual tags. */
public final class GeneralItemContext {
    private GeneralItemContext() {}
    public static SceneModelFeatures.ItemContext of(LivingEntity entity,boolean left,boolean firstPerson) {
        boolean main=left==(entity.getMainArm()==HumanoidArm.LEFT);var hand=main?net.minecraft.world.InteractionHand.MAIN_HAND:net.minecraft.world.InteractionHand.OFF_HAND;
        var stack=entity.getItemInHand(hand);var item=stack.getItem();var categories=new HashSet<String>();
        if(!stack.isEmpty())categories.add("item");String blockId="";var blockTags=new HashSet<String>();
        if(item instanceof BlockItem blockItem){categories.add("block");var block=blockItem.getBlock();blockId=BuiltInRegistries.BLOCK.getKey(block).toString();block.builtInRegistryHolder().tags().forEach(t->blockTags.add(t.location().toString()));
            if(block instanceof TorchBlock)categories.add("torch");if(block instanceof LanternBlock)categories.add("lantern");
            if(block instanceof StairBlock)categories.add("stairs");if(block instanceof SlabBlock)categories.add("slab");
            if(block instanceof FenceBlock)categories.add("fence");if(block instanceof FenceGateBlock)categories.add("fence_gate");if(block instanceof WallBlock)categories.add("wall");
            if(block instanceof DoorBlock)categories.add("door");if(block instanceof TrapDoorBlock)categories.add("trapdoor");
            if(block instanceof BedBlock)categories.add("bed");if(block instanceof ChestBlock)categories.add("chest");if(block instanceof ShulkerBoxBlock)categories.add("shulker_box");
            if(block instanceof FallingBlock)categories.add("falling_block");if(block instanceof BushBlock)categories.add("plant");if(block instanceof LeavesBlock)categories.add("leaves");
            if(block instanceof AbstractGlassBlock)categories.add("glass");if(block instanceof SignBlock)categories.add("sign");if(block instanceof SkullBlock)categories.add("skull");
        }
        if(item instanceof SwordItem)categories.add("sword");if(item instanceof DiggerItem)categories.add("tool");
        if(item instanceof PickaxeItem)categories.add("pickaxe");if(item instanceof AxeItem)categories.add("axe");if(item instanceof ShovelItem)categories.add("shovel");if(item instanceof HoeItem)categories.add("hoe");
        if(item instanceof BowItem)categories.add("bow");if(item instanceof CrossbowItem)categories.add("crossbow");if(item instanceof ShieldItem)categories.add("shield");
        if(item instanceof TridentItem)categories.add("trident");if(item instanceof FishingRodItem)categories.add("fishing_rod");
        if(stack.isEdible())categories.add("food");if(stack.getUseAnimation()==UseAnim.DRINK)categories.add("drink");
        if(com.elfmcys.ysm.client.compat.tacz.TACZCompat.getGunId(stack)!=null||com.elfmcys.ysm.client.compat.swarfare.SWarfareCompat.isGun(stack))categories.add("gun");
        if(com.elfmcys.ysm.client.compat.slashblade.SlashBladeCompat.isSlashBladeItem(stack))categories.add("slashblade");
        var tags=new HashSet<String>();stack.getTags().forEach(t->tags.add(t.location().toString()));
        var state=entity.isUsingItem()&&entity.getUsedItemHand()==hand?SceneModelFeatures.ItemState.USING:
            entity.swinging&&entity.swingingArm==hand?SceneModelFeatures.ItemState.SWINGING:
            item instanceof CrossbowItem&&CrossbowItem.isCharged(stack)?SceneModelFeatures.ItemState.CHARGED:SceneModelFeatures.ItemState.IDLE;
        return new SceneModelFeatures.ItemContext(BuiltInRegistries.ITEM.getKey(item).toString(),tags,categories,left,main,firstPerson,state,stack.getUseAnimation().name(),blockId,blockTags);
    }
}
