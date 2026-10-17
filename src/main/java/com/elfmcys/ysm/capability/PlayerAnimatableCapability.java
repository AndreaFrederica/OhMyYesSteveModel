package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.client.compat.FirstPersonCompat;
import com.elfmcys.ysm.client.compat.bettercombat.BetterCombatCompat;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.molang.runtime.Struct;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public final class PlayerAnimatableCapability extends CustomPlayerEntity {
    private final ClientRoamingSession roamingSession;

    public PlayerAnimatableCapability(Player player) {
        super(player, player instanceof LocalPlayer, true);
        roamingSession = new ClientRoamingSession(
                player, player instanceof LocalPlayer, this::reloadGeoModel);
    }

    @Override
    protected PlayerStateTracker createStateTracker(Player entity) {
        return new PlayerStateTracker(entity, entity instanceof LocalPlayer);
    }

    public PlayerStateTracker getStateTracker() {
        return (PlayerStateTracker) super.getStateTracker();
    }

    @Override
    public @Nullable Struct getRoamingStruct() {
        return roamingSession.currentStruct();
    }

    @Override
    protected void onModelRenderTargetLoaded(ModelRenderTarget newModel) {
        super.onModelRenderTargetLoaded(newModel);
        roamingSession.modelLoaded(getModelRenderTarget().modelHash().roamingHash());
        if(newModel.generalMeshResources()!=null)roamingSession.generalModelLoaded();
    }

    @Override
    protected void resetModelRenderTarget() {
        roamingSession.modelReset();
        super.resetModelRenderTarget();
    }

    @Override
    public void onLoadGeoModel(AnimatedGeoModel model) {
        super.onLoadGeoModel(model);
        roamingSession.geoModelLoaded();
    }

    @Override
    protected void resetGeoModel() {
        roamingSession.geoModelReset();
        super.resetGeoModel();
        if(isGeneralMesh())roamingSession.generalModelLoaded();
    }

    @Override
    protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<Player>> animationEvent, boolean update) {
        super.codeAnimation(animationEvent, update);

        // 更新第一人称相机偏移与头部隐藏
        AnimatedGeoModel model = getLoadedGeoModel();
        if (model != null && isLocalPlayer()) {
            var ctx = animationEvent.getRenderContext();
            if (ctx.firstPersonMod()) {
                if (model.getFirstPersonAllHead() != null) {
                    model.getFirstPersonAllHead().setHidden(true);
                }
                if (model.getFirstPersonViewLocator() != null) {
                    FirstPersonCompat.setHeadPos(model.getFirstPersonViewLocator().getPivot().y * getHeightScale());
                } else if (update) {
                    var head = model.getFirstPersonHead();
                    if (head != null) {
                        FirstPersonCompat.setHeadPos(head.getPivotY() * getHeightScale());
                    }
                }
            }
        }
    }

    @Override
    protected void recoverLastCodedAnimation(boolean lastFrameUpdated) {
        super.recoverLastCodedAnimation(lastFrameUpdated);

        AnimatedGeoModel model = getLoadedGeoModel();
        if (model != null && isLocalPlayer()) {
            if ((FirstPersonCompat.isInstalled() || BetterCombatCompat.isInstalled()) && model.getFirstPersonAllHead() != null) {
                model.getFirstPersonAllHead().setHidden(false);
            }
        }
    }

    public void resetRoamingVars(int modelHashShort, Int2FloatOpenHashMap vars) {
        roamingSession.resetFromServer(modelHashShort, vars);
    }

    public void clearRoamingVars() {
        roamingSession.clearFromServer();
    }

    public boolean hasRoamingStorage(int hashShort) {
        return roamingSession.hasStorage(hashShort);
    }

    public void updateRemoteRoamingVars(int modelHashShort, Int2FloatMap vars) {
        roamingSession.updateRemote(modelHashShort, vars);
    }

    public void handleRoamingVarsChanges() {
        roamingSession.flushLocalChanges();
    }

    public RoamingSnapshot roamingSnapshot(int authoritativeModelKey) {
        return roamingSession.snapshot(authoritativeModelKey);
    }

    /** The roaming namespace of the applied model, or null before one is loaded. */
    public Integer localRoamingKey() {
        return roamingSession.currentRoamingKey();
    }

    public record RoamingSnapshot(int modelKey, Int2FloatOpenHashMap values) {
    }

    public void moveFrom(PlayerAnimatableCapability source) {
        roamingSession.moveFrom(source.roamingSession);
        updateModelAndTexture(source.getModelHash(), source.textureName);
        setDisabled(source.isDisabled());
    }

    @Override
    @NotNull
    protected HumanoidResourceHolder createResourceHolder(ResourceLease lease, boolean isFallback) {
        return new HumanoidResourceHolder(lease, isFallback);
    }

}
