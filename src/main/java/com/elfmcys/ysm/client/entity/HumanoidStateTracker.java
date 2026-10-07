package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.compat.immersivemelodies.ImmersiveMelodiesCompat;
import com.elfmcys.ysm.geckolib3.model.EntityStateTracker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

public class HumanoidStateTracker<T extends EntityLivingBase> extends EntityStateTracker<T> {
    private ImmersiveMelodiesCompat.ImmersiveMelodiesData imData = new ImmersiveMelodiesCompat.ImmersiveMelodiesData();

    private ItemStack mainhandItemStack = ItemStack.EMPTY;
    private ItemStack offhandItemStack = ItemStack.EMPTY;

    public HumanoidStateTracker(T entity) {
        super(entity);
    }

    @Override
    public void reset() {
        mainhandItemStack = ItemStack.EMPTY;
        offhandItemStack = ItemStack.EMPTY;
        super.reset();
    }

    @Override
    protected void updateRenderTickData(float currentRenderTick, float lastRenderTick, float partialTicks) {
        super.updateRenderTickData(currentRenderTick, lastRenderTick, partialTicks);
        ImmersiveMelodiesCompat.updateMelodyProgress(this.entity, imData);
    }

    public ItemStack getHandItem(EnumHand hand) {
        if (hand == EnumHand.MAIN_HAND) {
            return mainhandItemStack;
        } else {
            return offhandItemStack;
        }
    }

    public void setHandItem(ItemStack stack, EnumHand hand) {
        if (hand == EnumHand.MAIN_HAND) {
            this.mainhandItemStack = stack;
        } else {
            this.offhandItemStack = stack;
        }
    }

    public ImmersiveMelodiesCompat.ImmersiveMelodiesData getImmersiveMelodiesData() {
        return imData;
    }
}
