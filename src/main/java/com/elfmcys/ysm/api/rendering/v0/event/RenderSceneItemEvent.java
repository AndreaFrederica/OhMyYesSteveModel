package com.elfmcys.ysm.api.rendering.v0.event;

import cc.sirrus.ysmlib.scene.SceneModelFeatures;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Event;

/** Per-hand weapon hook. Stack already contains the evaluated, configured socket transform. */
public final class RenderSceneItemEvent extends Event {
    public enum Result { PASS, HANDLED, HIDDEN }
    public final LivingEntity entity;
    public final ItemStack item;
    public final SceneModelFeatures.ItemContext context;
    public final String renderer;
    public final PoseStack pose;
    public final MultiBufferSource buffers;
    public final int light;
    private Result result=Result.PASS;
    public RenderSceneItemEvent(LivingEntity entity,ItemStack item,SceneModelFeatures.ItemContext context,String renderer,PoseStack pose,MultiBufferSource buffers,int light){this.entity=entity;this.item=item;this.context=context;this.renderer=renderer;this.pose=pose;this.buffers=buffers;this.light=light;}
    public Result result(){return result;}
    public void result(Result result){this.result=java.util.Objects.requireNonNull(result);}
}
