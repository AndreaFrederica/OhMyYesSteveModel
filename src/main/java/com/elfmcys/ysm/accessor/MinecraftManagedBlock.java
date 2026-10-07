package com.elfmcys.ysm.accessor;

import java.util.function.BooleanSupplier;

/**
 * 1.12.2 无 {@code BlockableEventLoop.managedBlock}。本接口由 {@code MinecraftMixin}
 * 实现，提供「在客户端线程上抽干 {@code scheduledTasks} 直到条件成立」的等价物。
 * 调用方必须已在客户端线程。
 */
public interface MinecraftManagedBlock {
    void ysm$managedBlock(BooleanSupplier isDone);
}
