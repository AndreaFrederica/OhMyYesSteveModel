package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.accessor.MinecraftManagedBlock;
import com.elfmcys.ysm.client.event.LocalPlayerRespawnEvent;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Queue;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin implements MinecraftManagedBlock {
    @Final
    @Shadow
    private Queue<FutureTask<?>> scheduledTasks;

    public void ysm$managedBlock(BooleanSupplier isDone) {
        while (!isDone.getAsBoolean()) {
            FutureTask<?> task;
            //noinspection SynchronizeOnNonFinalField
            synchronized (this.scheduledTasks) {
                task = this.scheduledTasks.poll();
            }
            if (task == null) {
                Thread.yield();
                continue;
            }
            Util.runTask(task, YesSteveModel.LOGGER);
        }
    }

    @WrapMethod(method = "setDimensionAndSpawnPlayer")
    private void ysm$localPlayerCloned(int dimension, Operation<Void> original) {
        if (!YesSteveModel.isAvailable()) {
            original.call(dimension);
            return;
        }

        Minecraft self = (Minecraft) (Object) this;
        EntityPlayerSP oldPlayer = self.player;
        original.call(dimension);
        LocalPlayerRespawnEvent.onCloned(oldPlayer, self.player);
    }
}
