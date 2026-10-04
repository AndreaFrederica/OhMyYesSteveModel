package com.elfmcys.ysm.model.resource.client;

import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.util.CleanerUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.lang.ref.Cleaner;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ModelRenderTarget implements AutoCloseable {
    private final Hash256 modelHash;
    private final String renderTargetId;
    private final RenderTargetResources targetResources;
    private final CommonAsset assets;

    private final ModelInfoView modelInfo;
    private final CleanupState cleanupState;
    private final Cleaner.Cleanable cleanable;
    private volatile ResourceLocation textureId;

    public ModelRenderTarget(Hash256 modelHash, String renderTargetId, RenderTargetResources targetResources,
                             CommonAsset assets, ModelInfoView modelInfo) {
        this.modelHash = Objects.requireNonNull(modelHash, "modelHash");
        this.renderTargetId = Objects.requireNonNull(renderTargetId, "renderTargetId");
        this.targetResources = Objects.requireNonNull(targetResources, "targetResources");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.modelInfo = Objects.requireNonNull(modelInfo, "modelInfo");
        cleanupState = new CleanupState(targetResources);
        cleanable = CleanerUtil.ref(this, cleanupState, CleanupState::clean);
    }

    public Hash256 modelHash() {
        return modelHash;
    }

    public String renderTargetId() {
        return renderTargetId;
    }

    public PlayerModelResources playerResources() {
        return targetResources instanceof PlayerModelResources player ? player : null;
    }

    public GeneralMeshModelResources generalMeshResources() {
        return targetResources instanceof GeneralMeshModelResources mesh ? mesh : null;
    }

    public CommonAsset assets() {
        return assets;
    }

    public ProjectileModelResources projectileResources() {
        return targetResources instanceof ProjectileModelResources projectile ? projectile : null;
    }

    public VehicleModelResources vehicleResources() {
        return targetResources instanceof VehicleModelResources vehicle ? vehicle : null;
    }

    public ModelInfoView info() {
        return modelInfo;
    }

    public ResourceLocation textureId() {
        var current = textureId;
        if (current == null) {
            throw new IllegalStateException("Model render target is not published");
        }
        return current;
    }

    public void adoptTexture(ResourceLocation textureId, AutoCloseable binding) {
        Objects.requireNonNull(textureId, "textureId");
        cleanupState.adopt(binding);
        this.textureId = textureId;
    }

    public String getDisplayName(String defaultName) {
        var info = modelInfo.getMetadata();
        if (info != null) {
            return LanguageManager.getI18n(this, "metadata.name", info.name());
        }
        return defaultName;
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    private static final class CleanupState {
        private final RenderTargetResources targetResources;
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final AtomicReference<AutoCloseable> textureBinding = new AtomicReference<>();

        private CleanupState(RenderTargetResources targetResources) {
            this.targetResources = targetResources;
        }

        private void adopt(AutoCloseable binding) {
            Objects.requireNonNull(binding, "binding");
            if (cleaned.get() || !textureBinding.compareAndSet(null, binding)) {
                throw new IllegalStateException("Model render target cannot adopt a texture binding");
            }
        }

        private void clean() {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            var minecraft = Minecraft.getInstance();
            if (minecraft != null && !minecraft.isSameThread()) {
                minecraft.execute(this::destroy);
            } else {
                destroy();
            }
        }

        private void destroy() {
            RenderSystem.assertOnRenderThread();
            RuntimeException releaseFailure = null;
            var binding = textureBinding.getAndSet(null);
            if (binding != null) {
                try {
                    binding.close();
                } catch (Exception error) {
                    releaseFailure = new IllegalStateException(
                            "Failed to release model texture binding", error);
                }
            }
            if (targetResources instanceof PlayerModelResources player) {
                player.animations().close();
                player.fpArmAnimations().close();
            } else if (targetResources instanceof ProjectileModelResources projectile) {
                projectile.animations().close();
            } else if (targetResources instanceof VehicleModelResources vehicle) {
                vehicle.animations().close();
            } else if (targetResources instanceof GeneralMeshModelResources mesh) {
                try {
                    mesh.close();
                } catch (RuntimeException error) {
                    if (releaseFailure == null) releaseFailure = error;
                    else releaseFailure.addSuppressed(error);
                }
            }
            var models = Collections.newSetFromMap(new IdentityHashMap<GeoModel, Boolean>());
            if (targetResources instanceof PlayerModelResources player) {
                for (var variant : player.variants().values()) {
                    models.add(variant.mainModel());
                    models.add(variant.armModel());
                }
            } else if (targetResources instanceof ProjectileModelResources projectile) {
                models.add(projectile.model());
            } else if (targetResources instanceof VehicleModelResources vehicle) {
                models.add(vehicle.model());
            }
            models.forEach(GeoModel::close);
            if (releaseFailure != null) {
                throw releaseFailure;
            }
        }
    }
}
