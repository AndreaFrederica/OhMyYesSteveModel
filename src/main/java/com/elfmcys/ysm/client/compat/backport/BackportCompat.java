package com.elfmcys.ysm.client.compat.backport;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;

// 向下移植模组杂项兼容
public final class BackportCompat {
    private static final String J_CROSSBOW_MOD_ID = "crossbow";
    private static boolean J_CROSSBOW_INSTALLED = false;
    private static final String S_CROSSBOWS_MOD_ID = "crossbows";
    private static boolean S_CROSSBOWS_INSTALLED = false;
    private static final String OCEANIC_EXPANSE_MOD_ID = "oe";
    private static boolean OCEANIC_EXPANSE_INSTALLED = false;
    private static final String TRIDENT_MOD_ID = "trident";
    private static boolean TRIDENT_INSTALLED = false;
    private static final String FUTURE_MC_MOD_ID = "futuremc";
    private static final String DEPTHS_UPDATE_MOD_ID = "depthsupdate";
    private static final String DEEPER_DEPTHS_MOD_ID = "deeperdepths";
    private static final String SUI_KE_CHERRY_MOD_ID = "suikecherry";

    private BackportCompat() {
    }

    public static void init() {
        if (Loader.isModLoaded(J_CROSSBOW_MOD_ID)) {
            J_CROSSBOW_INSTALLED = true;
            JCrossbowCompatInner.init();
        }
        if (Loader.isModLoaded(S_CROSSBOWS_MOD_ID)) {
            S_CROSSBOWS_INSTALLED = true;
            SCrossbowsCompatInner.init();
        }
        if (Loader.isModLoaded(OCEANIC_EXPANSE_MOD_ID)) {
            OCEANIC_EXPANSE_INSTALLED = true;
            OECompatInner.init();
        }
        if (Loader.isModLoaded(TRIDENT_MOD_ID)) {
            TRIDENT_INSTALLED = true;
            TridentCompatInner.init();
        }
        if (Loader.isModLoaded(FUTURE_MC_MOD_ID)) {
            FMCCompatInner.init();
        }
        if (Loader.isModLoaded(DEPTHS_UPDATE_MOD_ID)) {
            DepthsUpdateCompatInner.init();
        }
        if (Loader.isModLoaded(DEEPER_DEPTHS_MOD_ID)) {
            DeeperDepthsCompatInner.init();
        }
        if (Loader.isModLoaded(SUI_KE_CHERRY_MOD_ID)) {
            SuiKeCherryCompatInner.init();
        }
    }

    public static boolean isCrossbowCharged(ItemStack stack) {
        return (J_CROSSBOW_INSTALLED && JCrossbowCompatInner.isCharged(stack)) ||
                (S_CROSSBOWS_INSTALLED && SCrossbowsCompatInner.isCharged(stack));
    }

    public static boolean isAutoSpinAttack(EntityLivingBase living) {
        return (OCEANIC_EXPANSE_INSTALLED && OECompatInner.isAutoSpinAttack(living)) ||
                (TRIDENT_INSTALLED && TridentCompatInner.isAutoSpinAttack(living));
    }

    public static boolean getTridentAutoSpinAttack(EntityLivingBase living) {
        return TRIDENT_INSTALLED && TridentCompatInner.isAutoSpinAttack(living);
    }

    public static void setTridentAutoSpinAttack(EntityLivingBase living, boolean enable) {
        if (TRIDENT_INSTALLED) {
            TridentCompatInner.setAutoSpinAttack(living, enable);
        }
    }
}
