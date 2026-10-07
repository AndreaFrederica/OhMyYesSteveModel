package com.elfmcys.ysm.client.renderer.replace;

import com.elfmcys.ysm.capability.ProjectileAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import net.minecraft.entity.Entity;

public class ProjectileRendererReplace {
    public static boolean renderInMixin(Entity entity, double x, double y, double z, float yaw, float partialTick) {
        var cap = entity.getCapability(ProjectileAnimatableCapabilityProvider.CAP, null);
        if (cap != null) {
            if (cap.isInitialized() && cap.isModelPresent()) {
                RegisterEntityRenderersEvent.getProjectRenderer().doRender(entity, x, y, z, yaw, partialTick);
                return false;
            }
        }
        return true;
    }
}
