package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.Hash256;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import org.jetbrains.annotations.Nullable;

public class VehicleModelInfoCapability {
    private Hash256 modelHash;
    private boolean initialized = false;
    private Object2FloatOpenHashMap<String> molangVarsServerBound = new Object2FloatOpenHashMap<>();

    public void update(Hash256 modelHash, Object2FloatOpenHashMap<String> molangVarsServerBound) {
        this.modelHash = modelHash;
        this.initialized = true;
        this.molangVarsServerBound = molangVarsServerBound;
    }

    public void copyFrom(VehicleModelInfoCapability source) {
        this.modelHash = source.modelHash;
        this.initialized = source.initialized;
        this.molangVarsServerBound = source.molangVarsServerBound;
    }

    public Hash256 getOwnerModelHash() {
        return modelHash;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public Object2FloatOpenHashMap<String> getMolangVarsServerBound() {
        return molangVarsServerBound;
    }

    public NBTTagCompound serializeNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("owner_model_hash", modelHash == null ? "" : modelHash.toString());
        tag.setBoolean("initialized", initialized);

        NBTTagCompound varsTag = new NBTTagCompound();
        molangVarsServerBound.object2FloatEntrySet().fastForEach(varsEntry -> {
            varsTag.setFloat(varsEntry.getKey(), varsEntry.getFloatValue());
        });
        tag.setTag("molang_vars_server_bound", varsTag);

        return tag;
    }

    public void deserializeNBT(NBTTagCompound nbt) {
        var stored = nbt.getString("owner_model_hash");
        try {
            this.modelHash = stored.isEmpty() ? null : Hash256.parse(stored);
        } catch (IllegalArgumentException ignored) {
            this.modelHash = null;
        }
        this.initialized = nbt.getBoolean("initialized");

        this.molangVarsServerBound.clear();
        var varsTag = nbt.getCompoundTag("molang_vars_server_bound");
        for (var name : varsTag.getKeySet()) {
            var value = varsTag.getFloat(name);
            this.molangVarsServerBound.put(name, value);
        }
    }

    public static class Storage implements Capability.IStorage<VehicleModelInfoCapability> {
        @Nullable
        @Override
        public NBTBase writeNBT(Capability<VehicleModelInfoCapability> capability, VehicleModelInfoCapability instance, EnumFacing side) {
            return instance.serializeNBT();
        }

        @Override
        public void readNBT(Capability<VehicleModelInfoCapability> capability, VehicleModelInfoCapability instance, EnumFacing side, NBTBase nbt) {
            instance.deserializeNBT((NBTTagCompound) nbt);
        }
    }
}
