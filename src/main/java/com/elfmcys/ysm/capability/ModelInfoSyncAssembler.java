package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.network.forge.PlayerStateHandler;
import com.elfmcys.ysm.network.protocol.ModelReferenceCodec;
import com.elfmcys.ysm.proto.network.ModelSelectionState;
import com.elfmcys.ysm.proto.network.MolangVariable;
import com.elfmcys.ysm.proto.network.PlayerStateUpdate;
import com.elfmcys.ysm.proto.network.RoamingState;
import java.util.Optional;
import net.minecraft.entity.player.EntityPlayerMP;

public final class ModelInfoSyncAssembler {
    private ModelInfoSyncAssembler() {
    }

    public static Optional<PlayerStateUpdate> build(EntityPlayerMP player,
                                                                  ModelInfoCapability capability,
                                                                  ServerCatalog snapshot) {
        return ModelSelectionService.resolve(capability, snapshot).map(model -> {
            var hash = model.representation().modelId();
            var builtinDefault = model.location().rootKind() == CatalogRootKind.BUILTIN
                    && model.location().path().value().equals("default") ? hash : null;
            var reference = ModelReferenceCodec.create(hash, builtinDefault);
            var update = PlayerStateHandler.newFull(player)
                    .setModel(ModelSelectionState.newBuilder()
                            .setModel(reference)
                            .setTextureId(capability.getSelectTexture())
                            .setDisabled(capability.isDisabled())
                            .build());
            capability.getPropertiesTracker().populateFull(update, player);
            var variables = capability.roamingVariables().variables(hash);
            var roaming = RoamingState.newBuilder().setModelKey(hash.roamingHash());
            variables.object2FloatEntrySet().fastForEach(entry -> roaming.addVariables(
                    MolangVariable.newBuilder()
                            .setName(entry.getKey())
                            .setValue(entry.getFloatValue())
                            .build()));
            update.setRoaming(roaming.build());
            return update.build();
        });
    }
}
