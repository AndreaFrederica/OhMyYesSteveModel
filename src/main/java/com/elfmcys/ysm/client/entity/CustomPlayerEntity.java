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
    @Override
    public com.elfmcys.ysm.api.rendering.v0.TargetKind renderTargetKind() {
        return com.elfmcys.ysm.api.rendering.v0.TargetKind.PLAYER;
    }

    protected final boolean localPlayer;

    protected boolean isPlayingExtraAnimation = false;
    protected String extraAnimationName = "idle";
    protected boolean isExtraAnimationDirty = false;

    private List<IValue> syncHandler = null;

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
        super.resetGeoModel();
        isPlayingExtraAnimation = false;
        extraAnimationName = "idle";
        isExtraAnimationDirty = false;
    }

    public void playExtraAnimation(String animationName) {
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
        if (localPlayer && shouldTick) {
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
