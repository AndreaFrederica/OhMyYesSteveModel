package com.elfmcys.ysm.client.compat.jade;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.util.ModelIdUtil;
import mcp.mobius.waila.api.*;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@WailaPlugin
@SuppressWarnings("unused")
public class JadePlugin implements IWailaPlugin {
    @Override
    public void register(IWailaRegistrar reg) {
        reg.addConfig(I18n.format("config.jade.plugin_ysm"), YSMProvider.UID, I18n.format("config.jade.plugin_ysm.model_info"));
        reg.registerBodyProvider(new YSMProvider(), Entity.class);
    }

    private static class YSMProvider implements IWailaEntityProvider {
        private static final String UID = "ysm.model_info";

        @NotNull
        @Override
        public List<String> getWailaBody(Entity entity, List<String> tooltip, IWailaEntityAccessor entityAccessor, IWailaConfigHandler config) {
            if (!config.getConfig(UID)) return tooltip;
            if (entityAccessor.getEntity() instanceof EntityPlayer player) {
                var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
                if (cap != null) {
                    if (cap.isInitializedAndEnabled()) {
                        tooltip.add(I18n.format("top.yes_steve_model.model_info.id") +
                                cap.getModelRenderTarget().getDisplayName(ModelIdUtil.getFileNameFromPath(cap.getModelId())));
                    }
                }
            } else {
                var cap = entityAccessor.getEntity().getCapability(VehicleAnimatableCapabilityProvider.CAP, null);
                if (cap != null) {
                    if (cap.isInitialized() && cap.isModelPresent()) {
                        tooltip.add(I18n.format("top.yes_steve_model.model_info.id") +
                                cap.getModelRenderTarget().getDisplayName(ModelIdUtil.getFileNameFromPath(cap.getModelId())));
                    }
                }
            }
            return tooltip;
        }
    }
}
