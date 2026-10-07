package com.elfmcys.ysm.client.compat.backpack.sophisticated;

import com.elfmcys.ysm.client.animation.molang.CtrlBinding;
/*
import com.elfmcys.ysm.client.compat.curios.CuriosCompat;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.config.ClientConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.LoadingModList;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
*/

public class SophisticatedCompat {
    /*
    private static final ArtifactVersion MIN_VERSION = new DefaultArtifactVersion("3.24.25");
    private static final String MOD_ID = "sophisticatedbackpacks";
    private static boolean INSTALLED;
    private static boolean INCOMPATIBLE;
    */

    /**
     * 需要在资源加载后初始化
     */
    /*
    public static void init() {
        if (!ClientConfig.ENABLE_SOPHISTICATED_BACKPACK_COMPAT) {
            return;
        }

        var modFile = LoadingModList.get().getModFileById(MOD_ID);
        if (modFile != null) {
            if (modFile.getMods().get(0).getVersion().compareTo(MIN_VERSION) >= 0) {
                INSTALLED = true;
            } else {
                INCOMPATIBLE = true;
            }
        }
    }

    public static Optional<Pair<String, String>> getCompatibilityWarning() {
        if (INCOMPATIBLE) {
            return Optional.of(Pair.of("Sophisticated Backpacks", MIN_VERSION.toString()));
        }
        return Optional.empty();
    }

    public static void addLayer() {
        if (INSTALLED) {
            RegisterEntityRenderersEvent.getPlayerRenderer().addLayer(new YsmBackpackLayerRenderer());
        }
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }
    */

    public static void addBinding(CtrlBinding binding) {
        /*
        if (isInstalled()) {
            SophisticatedCtrlBinding.addInnerBinding(binding);
        } else {
            addEmptyBinding(binding);
        }
        */
        addEmptyBinding(binding);
    }

    /**
     * 没有安装此模组时，这些 molang 应该存在，否则会报错
     */
    private static void addEmptyBinding(CtrlBinding binding) {
        binding.livingEntityVar("has_sophisticated_backpack", ctx -> false);
    }

    /*
    @Nullable
    public static ItemStack getBackpackItemStack(LivingEntity livingEntity) {
        // 先尝试获取 curios 的背包
        if (CuriosCompat.isInstalled()) {
            var backpack = BackpackCuriosCompat.getCuriosBackpack(livingEntity);
            if (backpack != null) {
                return backpack;
            }
        }
        // 然后才是玩家护甲栏的背包
        if (livingEntity instanceof Player player) {
            return PlayerInventoryProvider.get().getBackpackFromRendered(player)
                    .map(backpackRenderInfo -> {
                        var itemStack = backpackRenderInfo.getBackpack();
                        if (!itemStack.isEmpty()) {
                            return itemStack;
                        }
                        return null;
                    })
                    .orElse(null);
        }
        return null;
    }
    */
}
