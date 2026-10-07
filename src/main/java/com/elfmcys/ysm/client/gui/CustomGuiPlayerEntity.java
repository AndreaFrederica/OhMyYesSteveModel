package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.animation.molang.PhysicsManager;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.client.event.ClientTickEvent;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.molang.context.DebugSource;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.player.EntityPlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class CustomGuiPlayerEntity extends CustomPlayerEntity implements IPreviewEntity {
    private final PreviewAnimationInfo guiAnimationInfo;
    private boolean allowEmitting;

    public CustomGuiPlayerEntity() {
        super(new FakePlayer(), false, false);
        guiAnimationInfo = new PreviewAnimationInfo();
    }

    void installPreviewResource(ResourceRequest request, ResourceLease lease) {
        super.installReadyForPreview(request, lease);
    }

    @Override
    public void reset() {
        guiAnimationInfo.setFocus("");
        guiAnimationInfo.setPreview("");
        guiAnimationInfo.setHover("");
        allowEmitting = false;
        super.reset();
    }

    @Override
    public boolean determineImmutableContext(RenderContext context) {
        return true;
    }

    @Override
    public @NotNull PreviewAnimationInfo getPreviewInfo() {
        return guiAnimationInfo;
    }

    @Override
    public PhysicsManager getPhysicsManager(AnimationEvent<?> event) {
        return physicsManager;
    }

    @Override
    public void setAllowEmitting(boolean value) {
        this.allowEmitting = value;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    protected boolean allowEmitting() {
        return allowEmitting;
    }

    @Override
    public int getFrameRateLimit() {
        return ClientTickEvent.getRefreshRate();
    }

    @Override
    public boolean isFakePlayer() {
        return true;
    }

    @Override
    protected GeoRenderData update(float partialTicks, RenderContext context) {
        if (entity instanceof FakePlayer fakePlayer && !fakePlayer.updateClientLevel()) {
            return null;
        }
        return super.update(partialTicks, context);
    }

    public static boolean isFakePlayer(EntityPlayer player) {
        return player instanceof FakePlayer;
    }

    @Override
    public DebugSource getDebugSource() {
        return null;
    }

    @Override
    protected @NotNull HumanoidResourceHolder createResourceHolder(ResourceLease lease, boolean isFallback) {
        return new HumanoidResourceHolder(lease, isFallback);
    }

    private static class FakePlayer extends AbstractClientPlayer {
        public FakePlayer() {
            super(Minecraft.getMinecraft().world, createRandomGameProfile());
        }

        private static GameProfile createRandomGameProfile() {
            var uuid = UUID.randomUUID();
            return new GameProfile(uuid, "ysm_" + uuid.toString().replace('-', '_'));
        }

        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }

        public boolean updateClientLevel() {
            var level = Minecraft.getMinecraft().world;
            if (level != null) {
                this.setWorld(level);
                return true;
            } else {
                return false;
            }
        }
    }
}
