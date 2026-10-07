package com.elfmcys.ysm.client.compat.touhoulittlemaid;

/*
import com.elfmcys.ysm.client.compat.touhoulittlemaid.event.CopyYsmModelEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
*/

/**
 * 这个类是客户端和服务端都可能用到的类
 */
/*
public class TlmCommonCompat {
    private static final String MOD_ID = "touhou_little_maid";

    public static boolean isInstalled() {
        return ModList.get().isLoaded(MOD_ID);
    }

    public static void registerEvent() {
        if (isInstalled()) {
            MinecraftForge.EVENT_BUS.register(new CopyYsmModelEvent());
        }
    }

    public static boolean isMaid(Entity entity) {
        if (isInstalled()) {
            return TlmCommonCompatInner.isMaid(entity);
        }
        return false;
    }

    public static void onProjectileSetOwner(Projectile projectile, Entity entity) {
        if (isInstalled()) {
            TlmCommonCompatInner.onProjectileSetOwner(projectile, entity);
        }
    }

    public static void setRouletteAnim(Entity entity, String classifyId, int extraAnimIndex) {
        if (isInstalled()) {
            TlmCommonCompatInner.setRouletteAnima(entity, classifyId, extraAnimIndex);
        }
    }

    public static boolean canControlMaid(Entity entity, ServerPlayer player) {
        return isInstalled() && TlmCommonCompatInner.canControlMaid(entity, player);
    }

    @OnlyIn(Dist.CLIENT)
    public static void handleExecuteMolang(Entity entity, String molangExpression) {
        if (isInstalled()) {
            TlmCommonCompatInner.handleExecuteMolang(entity, molangExpression);
        }
    }
}
*/
