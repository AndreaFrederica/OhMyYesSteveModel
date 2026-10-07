package com.elfmcys.ysm.client.compat.touhoulittlemaid;

/*
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.ProjectileModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleModelInfoCapabilityProvider;
import com.elfmcys.ysm.client.animation.molang.CustomMolangParser;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.capability.YsmMaidCapabilityProvider;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.molang.parser.ParseException;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.MinecraftStateHandler;
import com.elfmcys.ysm.proto.mixel.common.StringPair;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.commons.lang3.StringUtils;
*/

/**
 * 这个类是客户端和服务端都可能用到的类
 */
/*
public class TlmCommonCompatInner {
    static boolean isMaid(Entity entity) {
        return entity instanceof EntityMaid;
    }

    static boolean canControlMaid(Entity entity, ServerPlayer player) {
        return entity instanceof EntityMaid maid && maid.isOwnedBy(player)
                && maid.isAlive() && maid.level() == player.level()
                && maid.distanceToSqr(player) <= 64.0;
    }

    @OnlyIn(Dist.CLIENT)
    static void handleExecuteMolang(Entity entity, String molangExpression) {
        if (!(entity instanceof EntityMaid maid)) {
            return;
        }
        if (!maid.isYsmModel()) {
            return;
        }
        maid.getCapability(YsmMaidCapabilityProvider.CAP).ifPresent(cap -> {
            try {
                IValue value = CustomMolangParser.parseSingleExpressionUnsafe(molangExpression);
                cap.executeMolangExp(value, true, false, null);
            } catch (ParseException e) {
                YesSteveModel.LOGGER.error("Failed to execute molang " + molangExpression, e);
            }
        });
    }

    static void onProjectileSetOwner(Projectile projectile, Entity entity) {
        if (!(entity instanceof EntityMaid maid)) {
            return;
        }
        if (maid.isYsmModel()) {
            projectile.getCapability(ProjectileModelInfoCapabilityProvider.CAP).ifPresent(cap -> {
                // TODO: 实现女仆的 roaming 变量
                ServerModelService.instance().catalog()
                        .flatMap(snapshot -> snapshot.findPath(maid.getYsmModelId()))
                        .ifPresent(model -> {
                            cap.init(model.representation().modelId(), new Object2FloatOpenHashMap<>());
                            var info = MinecraftStateHandler.projectile(projectile.getId(), cap);
                            NetworkHandler.broadcastToVisiblePlayers(info, projectile);
                        });
            });
        }
    }

    static void onVehicleSetModel(Entity vehicle, Entity entity) {
        if (!(entity instanceof EntityMaid maid)) {
            return;
        }
        if (maid.isYsmModel() && vehicle.getFirstPassenger() == entity) {
            vehicle.getCapability(VehicleModelInfoCapabilityProvider.CAP).ifPresent(cap -> {
                // TODO: 实现女仆的 roaming 变量
                ServerModelService.instance().catalog().flatMap(snapshot -> snapshot.findPath(maid.getYsmModelId()))
                        .ifPresent(model -> cap.update(model.representation().modelId(), new Object2FloatOpenHashMap<>()));
                var info = MinecraftStateHandler.vehicle(vehicle.getId(), cap);
                NetworkHandler.broadcastToVisiblePlayers(info, vehicle);
            });
        }
    }

    static void setRouletteAnima(Entity entity, String classifyId, int extraAnimIndex) {
        if (!(entity instanceof EntityMaid maid)) {
            return;
        }
        if (!maid.isYsmModel()) {
            return;
        }
        if (extraAnimIndex == -1) {
            maid.stopRouletteAnim();
            return;
        }
        String modelId = maid.getYsmModelId();
        ServerModelService.instance().catalog().flatMap(snapshot -> snapshot.findPath(modelId)).ifPresent(model -> {
            var settings = model.view().getManifest().info().settings();
            Iterable<StringPair> values = settings.extraAnimation();
            if (StringUtils.isNotBlank(classifyId) && !settings.extraAnimationClassify().isEmpty()) {
                for (var classify : settings.extraAnimationClassify()) {
                    if (classify.id().equals(classifyId)) {
                        values = classify.extraAnimation();
                        break;
                    }
                }
            }
            var index = 0;
            for (var value : values) {
                if (index++ == extraAnimIndex) {
                    maid.playRouletteAnim(value.key());
                    break;
                }
            }
        });
    }

}
*/
