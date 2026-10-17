package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.api.rendering.v0.event.RenderSceneItemEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.client.renderer.texture.OverlayTexture;

/** Optional weapon renderers consume explicit scene mounts, without manufacturing a legacy GeoRenderData. */
@Mod.EventBusSubscriber(value=Dist.CLIENT)
public final class GeneralWeaponRendering {
    private GeneralWeaponRendering() {}
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void render(RenderSceneItemEvent event) {
        if(event.result()!=RenderSceneItemEvent.Result.PASS)return;
        if(event.context.categories().contains("slashblade")&&(event.renderer.equals("vanilla")||event.renderer.startsWith("slashblade"))) {
            if(com.elfmcys.ysm.client.compat.slashblade.SlashBladeRender.renderSceneItem(event.item,event.renderer,event.pose,event.buffers,event.light))event.result(RenderSceneItemEvent.Result.HANDLED);return;
        }
        // Both integrations render offhand guns through FIXED and their custom item renderer.
        if(event.context.categories().contains("gun")&&(!event.context.main()||event.renderer.equals("tacz")||event.renderer.equals("superbwarfare"))) {
            com.elfmcys.ysm.client.compat.tacz.TACZCompat.openFlashShellRender(event.entity,event.item);
            try {net.minecraft.client.Minecraft.getInstance().getItemRenderer().renderStatic(event.item,ItemDisplayContext.FIXED,event.light,OverlayTexture.NO_OVERLAY,event.pose,event.buffers,event.entity.level(),event.entity.getId());}
            finally {com.elfmcys.ysm.client.compat.tacz.TACZCompat.stopFlashShellRender(event.item);}
            event.result(RenderSceneItemEvent.Result.HANDLED);
        }
    }
}
