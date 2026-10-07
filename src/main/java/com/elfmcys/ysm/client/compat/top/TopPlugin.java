package com.elfmcys.ysm.client.compat.top;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleModelInfoCapabilityProvider;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.proto.mixel.manifest.Manifest;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.Metadata;
import com.elfmcys.ysm.util.ModelIdUtil;
import java.util.function.Function;
import mcjty.theoneprobe.api.ElementAlignment;
import mcjty.theoneprobe.api.IProbeHitEntityData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.IProbeInfoEntityProvider;
import mcjty.theoneprobe.api.ITheOneProbe;
import mcjty.theoneprobe.api.ProbeMode;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings("unused")
public final class TopPlugin implements Function<ITheOneProbe, Void> {
    @Nullable
    @Override
    public Void apply(@Nullable ITheOneProbe probe) {
        if (probe != null) {
            probe.registerEntityProvider(new YSMProvider());
        }
        return null;
    }

    private static class YSMProvider implements IProbeInfoEntityProvider {
        private static final String ID = (new ResourceLocation(YesSteveModel.MOD_ID, "model_info")).toString();

        @Override
        public void addProbeEntityInfo(ProbeMode probeMode, IProbeInfo probeInfo, EntityPlayer sourcePlayer, World world, Entity entity, IProbeHitEntityData iProbeHitEntityData) {
            if (entity instanceof EntityPlayerMP player) {
                var cap = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
                if (cap != null) {
                    if (cap.isMandatory() || NetworkHandler.isPlayerChannelPresent(player)) {
                        var hash = cap.getModelId();
                        if (hash == null) {
                            return;
                        }
                        ServerModelService.instance().catalog().flatMap(snapshot -> snapshot.find(hash)).ifPresent(m -> {
                            var metadata = m.view().getManifest().info().metadata()
                                    .map(Metadata::name).orElse("");
                            probeInfo.horizontal(probeInfo.defaultLayoutStyle().alignment(ElementAlignment.ALIGN_CENTER))
                                    .text(formatKey("top.yes_steve_model.model_info.id") +
                                            StringUtils.defaultIfBlank(metadata, ModelIdUtil.getFileNameFromPath(
                                                    m.location().path().value())));
                        });
                    }
                }
            } else {
                var cap = entity.getCapability(VehicleModelInfoCapabilityProvider.CAP, null);
                if (cap != null) {
                    if (cap.isInitialized()) {
                        var hash = cap.getOwnerModelHash();
                        if (hash == null) {
                            return;
                        }
                        ServerModelService.instance().catalog().flatMap(snapshot -> snapshot.find(hash))
                                .filter(m -> hasVehicle(m.view().getManifest(), entity))
                                .ifPresent(m -> {
                                    var metadata = m.view().getManifest().info().metadata()
                                            .map(Metadata::name).orElse("");
                                    probeInfo.horizontal(probeInfo.defaultLayoutStyle().alignment(ElementAlignment.ALIGN_CENTER))
                                            .text(formatKey("top.yes_steve_model.model_info.id") +
                                                    StringUtils.defaultIfBlank(metadata, ModelIdUtil.getFileNameFromPath(m.location().path().value())));
                                });
                    }
                }
            }
        }

        @Override
        public String getID() {
            return ID;
        }

        private static boolean hasVehicle(Manifest manifest,
                                          Entity entity) {
            var id = EntityList.getKey(entity);
            if (id == null) return false;
            for (var replacement : manifest.renderTargets()) {
                if (replacement.kind() != RenderTargetKind.RENDER_TARGET_KIND_VEHICLE) {
                    continue;
                }
                if (replacement.match().isEmpty()) {
                    continue;
                }
                for (var match : replacement.match()) {
                    if (id.toString().equals(match)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @SuppressWarnings("SameParameterValue")
        private static String formatKey(String langKey) {
            return IProbeInfo.STARTLOC + langKey + IProbeInfo.ENDLOC;
        }
    }
}
