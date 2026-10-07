package com.elfmcys.ysm.client.compat.touhoulittlemaid.client.event;

/*
import com.elfmcys.ysm.client.compat.touhoulittlemaid.capability.YsmMaidCapabilityProvider;
import com.github.tartaricacid.touhoulittlemaid.compat.ysm.event.YsmMaidClientTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public class YsmMaidTickEvent {
    @SubscribeEvent
    public void onTickYsmMaid(YsmMaidClientTickEvent event) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        EntityMaid maid = event.getMaid();
        if (player.getUUID().equals(maid.getOwnerUUID())) {
            submitRoamingVariableChanges(maid);
        }
    }

    private void submitRoamingVariableChanges(EntityMaid maid) {
        maid.getCapability(YsmMaidCapabilityProvider.CAP).ifPresent(cap -> {
            // TODO
        });
    }
}
*/
