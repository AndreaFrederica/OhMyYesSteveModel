package com.elfmcys.ysm.client.compat.touhoulittlemaid.event;

/*
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.ModelSelectionService;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.github.tartaricacid.touhoulittlemaid.item.ItemHakureiGohei;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityGarageKit;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityStatue;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class CopyYsmModelEvent {
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        Player player = event.getEntity();

        // 玩家必须是创造模式
        if (!player.isCreative()) {
            return;
        }

        // 玩家是拿着御币的
        if (!(player.getMainHandItem().getItem() instanceof ItemHakureiGohei)) {
            return;
        }

        BlockHitResult result = event.getHitVec();
        BlockPos blockPos = result.getBlockPos();
        BlockEntity te = player.level().getBlockEntity(blockPos);

        if (te instanceof TileEntityGarageKit kit) {
            applyGarageKitData(kit, player);
        } else if (te instanceof TileEntityStatue statue) {
            applyStatueData(statue, player);
        }
    }

    private void applyStatueData(TileEntityStatue statue, Player player) {
        if (!statue.isCoreBlock()) {
            return;
        }
        CompoundTag data = statue.getExtraMaidData();
        if (data == null) {
            return;
        }
        EntityType.byString(data.getString("id")).ifPresent(type -> {
            if (type.equals(InitEntities.MAID.get())) {
                applyPlayerInfo(player, data);
                statue.refresh();
            }
        });
    }

    private void applyGarageKitData(TileEntityGarageKit kit, Player player) {
        CompoundTag data = kit.getExtraData();
        EntityType.byString(data.getString("id")).ifPresent(type -> {
            if (type.equals(InitEntities.MAID.get())) {
                applyPlayerInfo(player, data);
                kit.setData(kit.getFacing(), data);
            }
        });
    }

    private void applyPlayerInfo(Player player, CompoundTag compound) {
        player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP).ifPresent(cap -> {
            String modelId = ServerModelService.current().flatMap(ServerModelService::catalog)
                    .map(snapshot -> ModelSelectionService.displayId(cap, snapshot))
                    .orElseGet(() -> cap.getModelId() == null
                            ? "default" : cap.getModelId().toString());
            String texture = cap.getSelectTexture();

            compound.putBoolean(EntityMaid.IS_YSM_MODEL_TAG, true);
            compound.putString(EntityMaid.YSM_MODEL_ID_TAG, modelId);
            compound.putString(EntityMaid.YSM_MODEL_TEXTURE_TAG, texture);
            compound.putInt(EntityMaid.YSM_ROAMING_UPDATE_FLAG_TAG, compound.getInt(EntityMaid.YSM_ROAMING_UPDATE_FLAG_TAG) + 1);
            CompoundTag roamingVarsTag = new CompoundTag();
            // TODO: 复制 roaming 变量
            compound.put(EntityMaid.YSM_ROAMING_VARS_TAG, roamingVarsTag);
        });
    }
}
*/
