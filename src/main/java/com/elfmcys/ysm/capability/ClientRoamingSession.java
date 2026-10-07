package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.client.animation.molang.roaming.LocalRoamingStruct;
import com.elfmcys.ysm.client.animation.molang.roaming.RemoteRoamingStruct;
import com.elfmcys.ysm.geckolib3.core.molang.util.StringPool;
import com.elfmcys.ysm.molang.runtime.Struct;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatMaps;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import net.minecraft.entity.player.EntityPlayer;

/** Owns client roaming-variable synchronization independently from animation/render compatibility. */
final class ClientRoamingSession {
    private final EntityPlayer player;
    private final boolean localPlayer;
    private final Runnable reloadModel;
    private final Int2ReferenceOpenHashMap<RemoteStorage> storage =
            new Int2ReferenceOpenHashMap<>(8);

    private int currentHash;
    private Struct currentStruct;

    ClientRoamingSession(EntityPlayer player, boolean localPlayer, Runnable reloadModel) {
        this.player = player;
        this.localPlayer = localPlayer;
        this.reloadModel = reloadModel;
    }

    Struct currentStruct() {
        return currentStruct;
    }

    void modelLoaded(int roamingHash) {
        currentHash = roamingHash;
    }

    void modelReset() {
        currentHash = 0;
    }

    /** The roaming namespace of the currently applied model, or null before one is loaded. */
    Integer currentRoamingKey() {
        return currentStruct == null ? null : currentHash;
    }

    void geoModelLoaded() {
        var current = storage.get(currentHash);
        if (current == null || current.variables == null) {
            currentStruct = null;
        } else if (localPlayer) {
            currentStruct = new LocalRoamingStruct(currentHash, current.variables);
        } else {
            currentStruct = new RemoteRoamingStruct(current.variables);
        }
    }

    void geoModelReset() {
        currentStruct = null;
    }

    void resetFromServer(int roamingHash, Int2FloatOpenHashMap variables) {
        var remote = storage.computeIfAbsent(roamingHash, ignored -> new RemoteStorage());
        if (localPlayer && remote.variables != null) {
            return;
        }
        remote.variables = variables;
        remote.mergePendingChanges();
        if (roamingHash != currentHash) {
            return;
        }
        currentStruct = localPlayer
                ? new LocalRoamingStruct(roamingHash, variables)
                : new RemoteRoamingStruct(variables);
        if (localPlayer) {
            reloadModel.run();
        }
    }

    boolean hasStorage(int roamingHash) {
        return storage.containsKey(roamingHash);
    }

    void clearFromServer() {
        storage.clear();
        currentStruct = null;
    }

    void updateRemote(int roamingHash, Int2FloatMap variables) {
        // The server also sends replay compatibility updates to LocalPlayer; discard them in normal play.
        if (localPlayer || variables.isEmpty()) {
            return;
        }
        var remote = storage.computeIfAbsent(roamingHash, ignored -> new RemoteStorage());
        if (remote.variables == null) {
            remote.pendingChanges.enqueue(variables);
        } else {
            remote.variables.putAll(variables);
        }
        updateVehicle(roamingHash, variables);
    }

    void flushLocalChanges() {
        if (!localPlayer || currentHash == 0
                || !(currentStruct instanceof LocalRoamingStruct local)
                || !local.isDirty()) {
            return;
        }
        var changes = local.popChanges();
        updateVehicle(changes.modelHashShort, changes.variables);

        var values = new Object2FloatOpenHashMap<String>(changes.variables.size());
        for (var entry : Int2FloatMaps.fastIterable(changes.variables)) {
            var name = StringPool.getString(entry.getIntKey());
            if (name != null && name.length() <= LocalRoamingStruct.MAX_NAME_LENGTH) {
                values.put(name, entry.getFloatValue());
            }
        }
        ClientProtocolGateway.reportRoamingChanges(currentHash, values);
    }

    PlayerAnimatableCapability.RoamingSnapshot snapshot(int authoritativeHash) {
        if (authoritativeHash == currentHash && currentStruct instanceof LocalRoamingStruct local) {
            return new PlayerAnimatableCapability.RoamingSnapshot(authoritativeHash, local.snapshotValues());
        }
        var remote = storage.get(authoritativeHash);
        var values = remote == null || remote.variables == null
                ? new Int2FloatOpenHashMap() : new Int2FloatOpenHashMap(remote.variables);
        return new PlayerAnimatableCapability.RoamingSnapshot(authoritativeHash, values);
    }

    void moveFrom(ClientRoamingSession source) {
        storage.putAll(source.storage);
        source.storage.clear();
        source.currentStruct = null;
    }

    private void updateVehicle(int roamingHash, Int2FloatMap variables) {
        if (roamingHash != currentHash || player.getRidingEntity() == null
                || player.getRidingEntity().getControllingPassenger() != player) {
            return;
        }
        var capability = player.getRidingEntity().getCapability(VehicleAnimatableCapabilityProvider.CAP, null);
        if (capability != null) capability.updateRoamingVars(variables);
    }

    private static final class RemoteStorage {
        private Int2FloatOpenHashMap variables;
        private final ObjectArrayFIFOQueue<Int2FloatMap> pendingChanges =
                new ObjectArrayFIFOQueue<>(4);

        private void mergePendingChanges() {
            while (!pendingChanges.isEmpty()) {
                variables.putAll(pendingChanges.dequeue());
            }
        }
    }
}
