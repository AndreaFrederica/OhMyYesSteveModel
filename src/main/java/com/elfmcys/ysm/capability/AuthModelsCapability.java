package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.Hash256;
import com.google.common.collect.Sets;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public class AuthModelsCapability {
    private Set<Hash256> authModels = Sets.newHashSet();

    public void addModel(Hash256 modelHash) {
        authModels.add(modelHash);
    }

    public void copyFrom(AuthModelsCapability source) {
        this.authModels = source.authModels;
    }

    public void removeModel(Hash256 modelHash) {
        authModels.remove(modelHash);
    }

    public boolean containModel(Hash256 modelHash) {
        return authModels.contains(modelHash);
    }

    public Set<Hash256> getAuthModels() {
        return authModels;
    }

    public void setAuthModels(Set<Hash256> authModels) {
        this.authModels = Sets.newHashSet(authModels);
    }

    public void clear() {
        authModels.clear();
    }

    public NBTTagList serializeNBT() {
        NBTTagList listTag = new NBTTagList();
        for (Hash256 modelHash : authModels) {
            listTag.appendTag(new NBTTagString(modelHash.toString()));
        }
        return listTag;
    }

    public void deserializeNBT(NBTTagList nbt) {
        this.authModels.clear();
        for (NBTBase tag : nbt) {
            if (tag instanceof NBTTagString string) {
                try {
                    authModels.add(Hash256.parse(string.getString()));
                } catch (IllegalArgumentException ignored) {
                    // Old path-based authorization entries are intentionally not migrated.
                }
            }
        }
    }

    public static class Storage implements Capability.IStorage<AuthModelsCapability> {
        @Nullable
        @Override
        public NBTBase writeNBT(Capability<AuthModelsCapability> capability, AuthModelsCapability instance, EnumFacing side) {
            return instance.serializeNBT();
        }

        @Override
        public void readNBT(Capability<AuthModelsCapability> capability, AuthModelsCapability instance, EnumFacing side, NBTBase nbt) {
            instance.deserializeNBT((NBTTagList) nbt);
        }
    }
}
