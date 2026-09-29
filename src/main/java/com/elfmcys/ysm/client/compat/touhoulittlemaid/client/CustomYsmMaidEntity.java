package com.elfmcys.ysm.client.compat.touhoulittlemaid.client;

import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.molang.runtime.Struct;
import com.github.tartaricacid.touhoulittlemaid.api.entity.IMaid;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.MaidModelInfo;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.IGeoEntity;
import com.github.tartaricacid.touhoulittlemaid.geckolib3.geo.animated.ILocationModel;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * 基于 CustomPlayerEntity 复制来的，基本上没做删除，试想尝试让女仆能调用轮盘动画之类的,所以就先预留着
 */
@OnlyIn(Dist.CLIENT)
public class CustomYsmMaidEntity extends CustomHumanoidEntity<EntityMaid> implements IGeoEntity {
    public static class MaidRenderData extends com.elfmcys.ysm.geckolib3.geo.GeoRenderData {
        public ILocationModel locations = com.elfmcys.ysm.client.compat.touhoulittlemaid.util.TlmConverterHelper.EMPTY;
    }

    @Override
    public com.elfmcys.ysm.api.rendering.v0.TargetKind renderTargetKind() {
        return com.elfmcys.ysm.api.rendering.v0.TargetKind.MAID;
    }

    @Override
    protected com.elfmcys.ysm.geckolib3.geo.GeoRenderData createRenderData() { return new MaidRenderData(); }

    @Override
    protected void extractRenderData(com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent<?> event,
                                     com.elfmcys.ysm.geckolib3.geo.GeoRenderData data) {
        super.extractRenderData(event, data);
        ((MaidRenderData) data).locations = com.elfmcys.ysm.client.compat.touhoulittlemaid.util.TlmConverterHelper.snapshot(
                getLoadedGeoModel(), data.modelState);
    }

    private ILocationModel layerLocations = com.elfmcys.ysm.client.compat.touhoulittlemaid.util.TlmConverterHelper.EMPTY;
    public void withLayerLocations(MaidRenderData data, Runnable draw) {
        var previous = layerLocations;
        layerLocations = data.locations;
        try { draw.run(); } finally { layerLocations = previous; }
    }

    private MaidModelInfo maidInfo = new MaidModelInfo();

    public CustomYsmMaidEntity(EntityMaid player, boolean asyncUpdate) {
        super(player, asyncUpdate);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void onSetupAnimationController() {
        ((Consumer<CustomYsmMaidEntity>) getModelRenderTarget().playerResources().maidControllerFactory()).accept(this);
    }

    @Override
    protected @NotNull ResourceHolder createResourceHolder(ResourceLease lease, boolean isFallback) {
        return new HumanoidResourceHolder(lease, isFallback);
    }

    @Override
    protected MaidStateTracker createStateTracker(EntityMaid entity) {
        return new MaidStateTracker(entity);
    }

    @Override
    public MaidStateTracker getStateTracker() {
        return (MaidStateTracker) super.getStateTracker();
    }

    public boolean shouldResetRouletteAnim() {
        return this.entity.rouletteAnimDirty;
    }

    public void clearRouletteAnimDirty() {
        this.entity.rouletteAnimDirty = false;
    }

    public boolean isRouletteAnimPlaying() {
        return this.entity.rouletteAnimPlaying;
    }

    public String getRouletteAnim() {
        return this.entity.rouletteAnim;
    }

    public void setRemoteStruct(Object2FloatOpenHashMap<String> roamingVars) {
        // TODO
    }

    @Override
    public void updateRoamingVars(Object2FloatOpenHashMap<String> roamingVars) {
        // TODO
    }

    public Struct getRemoteStruct() {
        // TODO
        return null;
    }

    @Override
    protected void preAnimationSetup(float seekTime, boolean shouldTick) {
        super.preAnimationSetup(seekTime, shouldTick);

        getAnimationProcessor().putRemoteStruct(getRemoteStruct());
    }

    @Override
    public IMaid getMaid() {
        return this.entity;
    }

    @Override
    public MaidModelInfo getMaidInfo() {
        return maidInfo;
    }

    @Override
    public void setMaidInfo(MaidModelInfo maidModelInfo) {
        if (this.maidInfo != maidModelInfo) {
            this.maidInfo = maidModelInfo;
        }
    }

    @Override
    public ILocationModel getGeoModel() {
        return layerLocations;
    }

    @Override
    public void setYsmModel(String modelId, String texture) {
        updateModelAndTexture(modelId, texture);
    }
}
