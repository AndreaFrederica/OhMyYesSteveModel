package com.elfmcys.ysm.client.animation.molang.functions;

import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import com.elfmcys.ysm.molang.runtime.Function;
import net.minecraftforge.fml.common.Loader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ModVersion implements Function {
    @Nullable
    @Override
    public Object evaluate(@NotNull ExecutionContext<?> context, @NotNull ArgumentCollection arguments) {
        String modId = arguments.getAsString(context, 0);
        if (modId == null) {
            return null;
        }

        var modContainer = Loader.instance().getIndexedModList().get(modId);
        return modContainer != null ? modContainer.getVersion() : null;
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
