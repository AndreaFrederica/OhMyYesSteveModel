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

public class StarModelsCapability {
    private Set<Hash256> starModels = Sets.newHashSet();

    public void addModel(Hash256 modelHash) {
        starModels.add(modelHash);
    }

    public void copyFrom(StarModelsCapability source) {
        this.starModels = source.starModels;
    }

    public void removeModel(Hash256 modelHash) {
        starModels.remove(modelHash);
    }

    public boolean containModel(Hash256 modelHash) {
        return starModels.contains(modelHash);
    }

    public Set<Hash256> getStarModels() {
        return starModels;
    }

    public void setStarModels(Set<Hash256> starModels) {
        this.starModels = Sets.newHashSet(starModels);
    }

    public void clear() {
        starModels.clear();
    }

    public NBTTagList serializeNBT() {
        NBTTagList listTag = new NBTTagList();
        for (Hash256 modelHash : starModels) {
            listTag.appendTag(new NBTTagString(modelHash.toString()));
        }
        return listTag;
    }

    public void deserializeNBT(NBTTagList nbt) {
        this.starModels.clear();
        for (NBTBase tag : nbt) {
            if (tag instanceof NBTTagString string) {
                try {
                    starModels.add(Hash256.parse(string.getString()));
                } catch (IllegalArgumentException ignored) {
                    // Old path-based favorites are intentionally not migrated.
                }
            }
        }
    }

    public static class Storage implements Capability.IStorage<StarModelsCapability> {
        @Nullable
        @Override
        public NBTBase writeNBT(Capability<StarModelsCapability> capability, StarModelsCapability instance, EnumFacing side) {
            return instance.serializeNBT();
        }

        @Override
        public void readNBT(Capability<StarModelsCapability> capability, StarModelsCapability instance, EnumFacing side, NBTBase nbt) {
            instance.deserializeNBT((NBTTagList) nbt);
        }
    }
}
