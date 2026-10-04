package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.animation.molang.MolangEventWrapper;
import com.elfmcys.ysm.client.controller.collections.PlayerControllerCollection;
import com.elfmcys.ysm.geckolib3.core.AnimationState;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.molang.runtime.Struct;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public abstract class CustomPlayerEntity extends CustomHumanoidEntity<Player> implements IRoamingEntity {
    private YsmAnimationDriver skeletonDriver;
    private boolean skeletonDriverFailed;
    private String skeletonChannelWarning="";
    public cc.sirrus.ysmlib.scene.SceneModelProfile calibrateGeneralArms() {
        var scene=getGeneralMeshInstance();if(scene==null)throw new IllegalStateException("Model preview is still loading");
        prepareSkeletonDriver(scene);return skeletonDriver.calibrate(scene);
    }
    private void prepareSkeletonDriver(com.elfmcys.ysm.client.renderer.GeneralMeshInstance scene) {
        String source=scene.profile().retarget().sourceModel();
        if(skeletonDriver!=null&&!skeletonDriver.sourceModel().equals(source)){skeletonDriver.close();skeletonDriver=null;}
        if(skeletonDriver==null)skeletonDriver=new YsmAnimationDriver(this,source);
    }
    @Override protected boolean updateGeneralReference(com.elfmcys.ysm.client.renderer.GeneralMeshInstance scene) {
        prepareSkeletonDriver(scene);return skeletonDriver.reference(scene);
    }
    @Override protected boolean updateGeneralSkeleton(com.elfmcys.ysm.client.renderer.GeneralMeshInstance scene,float partialTicks) {
        if(skeletonDriverFailed)return false;
        try {
            String source=scene.profile().retarget().sourceModel();
            if(skeletonDriver!=null&&!skeletonDriver.sourceModel().equals(source)){skeletonDriver.close();skeletonDriver=null;}
            if(skeletonDriver==null)skeletonDriver=new YsmAnimationDriver(this,source);
            return skeletonDriver.drive(scene,partialTicks);
        } catch(UnsupportedOperationException failure) {
            if(!failure.getMessage().equals(skeletonChannelWarning)) {
                skeletonChannelWarning=failure.getMessage();
                com.elfmcys.ysm.YesSteveModel.LOGGER.warn("Unsupported YSM skeleton channel model={}: {}",getModelHash(),skeletonChannelWarning,failure);
            }
            scene.skeletonPose(java.util.Map.of(),java.util.Map.of());return false;
        } catch(RuntimeException failure) {
            skeletonDriverFailed=true;
            com.elfmcys.ysm.YesSteveModel.LOGGER.error("YSM to MMD skeleton binding failed model={}; source profile retained",getModelHash(),failure);
            scene.skeletonPose(java.util.Map.of(),java.util.Map.of());return false;
        }
    }
    @Override
    public com.elfmcys.ysm.api.rendering.v0.TargetKind renderTargetKind() {
        return com.elfmcys.ysm.api.rendering.v0.TargetKind.PLAYER;
    }

    protected final boolean localPlayer;

    protected boolean isPlayingExtraAnimation = false;
    protected String extraAnimationName = "idle";
    protected boolean isExtraAnimationDirty = false;

    private List<IValue> syncHandler = null;
    private String generalRequest = "";
    private String lastGeneralRequest = "";
    private com.elfmcys.ysm.client.animation.GeneralAnimationActions.Action generalAction;
    private double generalActionStart = Double.NaN;
    private com.elfmcys.ysm.model.domain.Hash256 generalRequestModel;

    private void finishGeneralAction() {
        stopExtraAnimation();
        if (localPlayer && NetworkHandler.isRemoteChannelPresent()) ClientProtocolGateway.stopSelfAnimation();
    }


    public CustomPlayerEntity(Player player, boolean localPlayer, boolean asyncUpdate) {
        super(player, asyncUpdate);
        this.localPlayer = localPlayer;
        if (player instanceof LocalPlayer) {
            setInitialized();
        }
    }

    @Override
    protected void onSetupAnimationController() {
        getModelRenderTarget().playerResources().playerControllerFactory().accept(this);
    }

    @Override
    public boolean determineImmutableContext(RenderContext context) {
        if (!super.determineImmutableContext(context)) {
            return false;
        }
        // 例外：local player 渲染 iris 阴影时应恒为第三人称，不能视为 immutable
        return !context.irisShadow() || !localPlayer
                || Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON;
    }

    @Nullable
    public Struct getRoamingStruct() {
        return null;
    }

    public boolean isLocalPlayer() {
        return localPlayer;
    }

    @Override
    protected String generalAnimationState() {
        if (!generalRequest.isEmpty()) {
            if (!java.util.Objects.equals(generalRequestModel, getModelHash())) stopExtraAnimation();
            else {
                if (generalAction == null) generalAction = com.elfmcys.ysm.client.animation.GeneralAnimationActions.resolve(getModelRenderTarget(), generalRequest);
                var action = generalAction;
                if (action != null) {
                    if (Double.isNaN(generalActionStart)) generalActionStart = entity.tickCount / 20.0;
                    double duration = action.animation().range().end() - action.animation().range().start();
                    isPlayingExtraAnimation = true;
                    extraAnimationName = generalRequest;
                    if (entity.isDeadOrDying() || (!action.loop() && duration > 0
                            && entity.tickCount / 20.0 - generalActionStart >= duration)) finishGeneralAction();
                    else return generalRequest;
                }
            }
        }
        if (entity.isDeadOrDying()) return "death";
        if (entity.isAutoSpinAttack()) return "riptide";
        if (entity.isSleeping()) return "sleep";
        if (entity.isFallFlying()) return "elytra_fly";
        if (entity.getAbilities().flying) return "fly";
        if (entity.isSwimming()) return "swim";
        if (entity.onClimbable()) return entity.getDeltaMovement().y > .001 ? "ladder_up"
                : entity.getDeltaMovement().y < -.001 ? "ladder_down" : "ladder_stillness";
        if (entity.isInWater() && !entity.onGround()) return "swim_stand";
        if (entity.hurtTime > 0) return "attacked";
        if (!entity.onGround() && !entity.isInWater()) return "jump";
        if (entity.getPose() == net.minecraft.world.entity.Pose.CROUCHING)
            return Math.abs(entity.walkDist - entity.walkDistO) > .001f ? "sneak" : "sneaking";
        if (entity.isSprinting()) return "run";
        if (Math.abs(entity.walkDist - entity.walkDistO) > .001f) return "walk";
        return "idle";
    }

    @Override
    protected void onModelRenderTargetLoaded(ModelRenderTarget newModel) {
        super.onModelRenderTargetLoaded(newModel);
        syncHandler = newModel.assets().eventHandlers().get(MolangEventWrapper.SYNC);
    }

    @Override
    protected void resetModelRenderTarget() {
        super.resetModelRenderTarget();
        syncHandler = null;
    }

    @Override
    protected void resetGeoModel() {
        if(skeletonDriver!=null){skeletonDriver.close();skeletonDriver=null;}
        skeletonDriverFailed=false;
        skeletonChannelWarning="";
        super.resetGeoModel();
        isPlayingExtraAnimation = false;
        extraAnimationName = "idle";
        isExtraAnimationDirty = false;
    }

    public void playExtraAnimation(String animationName) {
        // Keep canonical requests even when the target is still loading. FULL
        // authority refreshes must not restart the same request; new clicks carry a nonce.
        if (!com.elfmcys.ysm.model.domain.SceneActionId.base(animationName).isEmpty() || isGeneralMesh()) {
            if (com.elfmcys.ysm.model.domain.SceneActionId.base(animationName).isEmpty()) {
                var action = com.elfmcys.ysm.client.animation.GeneralAnimationActions.resolve(getModelRenderTarget(), animationName);
                if (action == null) return;
                animationName = com.elfmcys.ysm.model.domain.SceneActionId.request(
                        com.elfmcys.ysm.model.domain.SceneActionId.of(action.animation(), action.loop()), System.nanoTime());
            }
            if (animationName.equals(lastGeneralRequest)) return;
            generalRequest = animationName;lastGeneralRequest = animationName;generalRequestModel = getModelHash();
            generalActionStart = Double.NaN;generalAction = null;
            this.extraAnimationName = animationName;this.isPlayingExtraAnimation = true;this.isExtraAnimationDirty = true;
            return;
        }
        if (getAnimation(animationName)!= null) {
            this.extraAnimationName = animationName;
            this.isPlayingExtraAnimation = true;
            this.isExtraAnimationDirty = true;
        } else {
            this.isPlayingExtraAnimation = false;
        }
    }

    public void clearExtraAnimationDirty() {
        this.isExtraAnimationDirty = false;
    }

    public boolean isPlayingExtraAnimation() {
        return isPlayingExtraAnimation;
    }

    public boolean shouldResetExtraAnimation() {
        return isExtraAnimationDirty;
    }

    public String getExtraAnimationName() {
        return this.extraAnimationName;
    }

    public void stopExtraAnimation() {
        generalRequest = "";generalActionStart = Double.NaN;generalAction = null;
        this.isPlayingExtraAnimation = false;
    }

    @Override
    protected void preAnimationSetup(float seekTime, boolean shouldTick) {
        super.preAnimationSetup(seekTime, shouldTick);
        // 设置 roaming 变量
        getAnimationProcessor().putRemoteStruct(getRoamingStruct());
    }

    @Override
    protected void postAnimationSetup(float seekTime, boolean shouldTick) {
        super.postAnimationSetup(seekTime, shouldTick);
        if (localPlayer && shouldTick && !isGeneralMesh()) {
            if (isPlayingExtraAnimation() && getCodedAnimationStates(PlayerControllerCollection.CAP_CONTROLLER) == AnimationState.IDLE) {
                stopExtraAnimation();
                if (NetworkHandler.isRemoteChannelPresent()) {
                    ClientProtocolGateway.stopSelfAnimation();
                }
            }
        }
    }

    public void molangSync(FloatArrayList args) {
        if (syncHandler != null) {
            executeMolangExp(MolangEventWrapper.wrap(syncHandler, args), true, false, null);
        }
    }
}
