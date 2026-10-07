package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.model.domain.Hash256;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import org.jetbrains.annotations.Nullable;

/** Persistent player model selection and synchronization state. */
public final class ModelInfoCapability {
    private Hash256 modelId;
    private String selectTexture = "";
    private boolean mandatory;
    private boolean ignoreGrants;
    private boolean disabled;
    private boolean dirty;
    private final RoamingVariableStore roamingVariables = new RoamingVariableStore();
    private ServerDrivenPlayerPropertiesTracker propertiesTracker =
            new ServerDrivenPlayerPropertiesTracker();

    public void setModelAndTexture(Hash256 modelId, String selectTexture) {
        var keepIgnoreGrants = modelId != null && Objects.equals(this.modelId, modelId)
                && ignoreGrants;
        if (Objects.equals(this.modelId, modelId)
                && this.selectTexture.equals(selectTexture)
                && ignoreGrants == keepIgnoreGrants) {
            return;
        }
        this.modelId = modelId;
        this.selectTexture = selectTexture;
        ignoreGrants = keepIgnoreGrants;
        markDirty();
    }

    public void setCommandSelection(Hash256 modelId, String selectTexture,
                                    boolean ignoreGrants) {
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(selectTexture, "selectTexture");
        if (modelId.equals(this.modelId)
                && selectTexture.equals(this.selectTexture)
                && mandatory
                && this.ignoreGrants == ignoreGrants) {
            return;
        }
        this.modelId = modelId;
        this.selectTexture = selectTexture;
        mandatory = true;
        this.ignoreGrants = ignoreGrants;
        markDirty();
    }

    public boolean ignoresGrantsFor(Hash256 modelId) {
        return ignoreGrants && Objects.equals(this.modelId, modelId);
    }

    void clearIgnoreGrants() {
        if (ignoreGrants) {
            ignoreGrants = false;
            markDirty();
        }
    }

    public void moveFrom(ModelInfoCapability source) {
        modelId = source.modelId;
        selectTexture = source.selectTexture;
        mandatory = source.mandatory;
        ignoreGrants = source.ignoreGrants;
        disabled = source.disabled;
        propertiesTracker = source.propertiesTracker;
        roamingVariables.moveFrom(source.roamingVariables);
        markDirty();
    }

    public Hash256 getModelId() {
        return modelId;
    }

    public String getSelectTexture() {
        return selectTexture;
    }

    public void setSelectTexture(String selectTexture) {
        this.selectTexture = selectTexture;
        markDirty();
    }

    public void setDisabled(boolean disabled) {
        if (this.disabled != disabled) {
            this.disabled = disabled;
            markDirty();
        }
    }

    public void playAnimation(EntityPlayerMP player, String animation) {
        propertiesTracker.setExtraAnimation(player, !dirty, animation);
    }

    public void stopAnimation(EntityPlayerMP player) {
        propertiesTracker.setExtraAnimation(player, !dirty, "");
    }

    public void executeWithMolangVars(
            Consumer<Object2FloatOpenHashMap<String>> consumer) {
        roamingVariables.execute(modelId, consumer);
    }

    public Optional<Object2FloatOpenHashMap<String>> getMolangVars() {
        return roamingVariables.get(modelId);
    }

    public void updateRoamingVars(EntityPlayerMP player, int modelKey,
                                  Object2FloatMap<String> variables) {
        roamingVariables.update(modelKey, variables);
        propertiesTracker.updateMolangVars(player, !dirty,
                modelKey, variables);
    }

    public void applyClientAnimation(String animation) {
        propertiesTracker.acceptClientAnimation(animation);
    }

    public void applyClientRoaming(int modelKey,
                                   Object2FloatMap<String> variables,
                                   boolean full) {
        if (full) {
            roamingVariables.replace(modelKey, variables);
        } else {
            roamingVariables.update(modelKey, variables);
        }
        propertiesTracker.acceptClientRoaming();
    }

    public void trimRoamingStorage(IntSet retainedHashes) {
        roamingVariables.trim(retainedHashes);
    }

    RoamingVariableStore roamingVariables() {
        return roamingVariables;
    }

    public ServerDrivenPlayerPropertiesTracker getPropertiesTracker() {
        return propertiesTracker;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean consumeDirty() {
        var changed = dirty;
        dirty = false;
        return changed;
    }

    public void setMandatory(boolean mandatory) {
        if (this.mandatory != mandatory) {
            this.mandatory = mandatory;
            markDirty();
        }
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public NBTTagCompound serializeNBT() {
        var tag = new NBTTagCompound();
        tag.setString("model_hash", modelId == null ? "" : modelId.toString());
        tag.setString("select_texture", selectTexture);
        tag.setBoolean("mandatory", mandatory);
        tag.setBoolean("ignore_grants", ignoreGrants);
        tag.setBoolean("disabled", disabled);
        tag.setTag("molang_storage", roamingVariables.serialize());
        return tag;
    }

    public void deserializeNBT(NBTTagCompound tag) {
        var storedHash = tag.getString("model_hash");
        try {
            modelId = storedHash.isEmpty() ? null : Hash256.parse(storedHash);
        } catch (IllegalArgumentException invalidHash) {
            modelId = null;
        }
        selectTexture = tag.getString("select_texture");
        if (selectTexture.length() > 4 && selectTexture.toLowerCase().endsWith(".png")) {
            selectTexture = selectTexture.substring(0, selectTexture.length() - 4);
        }
        mandatory = tag.getBoolean("mandatory");
        ignoreGrants = modelId != null && tag.getBoolean("ignore_grants");
        disabled = tag.getBoolean("disabled");
        roamingVariables.deserialize(tag.getCompoundTag("molang_storage"));
    }

    public static class Storage implements Capability.IStorage<ModelInfoCapability> {
        @Nullable
        @Override
        public NBTBase writeNBT(Capability<ModelInfoCapability> capability, ModelInfoCapability instance, EnumFacing side) {
            return instance.serializeNBT();
        }

        @Override
        public void readNBT(Capability<ModelInfoCapability> capability, ModelInfoCapability instance, EnumFacing side, NBTBase nbt) {
            instance.deserializeNBT((NBTTagCompound) nbt);
        }
    }
}
