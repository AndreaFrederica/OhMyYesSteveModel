package com.elfmcys.ysm.client.particle;

import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.util.MathUtil;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import com.elfmcys.ysm.molang.runtime.Function;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.commons.lang3.StringUtils;
import org.joml.Vector3d;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@SideOnly(Side.CLIENT)
public class ParticleSpawner {
    private static final Cache<String, ParticleOptions> PARTICLE_OPTIONS_CACHE = CacheBuilder.newBuilder().expireAfterAccess(60, TimeUnit.SECONDS).build();

    public static boolean evalSpawnParticle(ExecutionContext<IContext<Entity>> context, Function.ArgumentCollection arguments, boolean absPos) {
        String id = arguments.getAsString(context, 0);
        if (StringUtils.isBlank(id)) {
            return false;
        }

        Vector3d pos = new Vector3d(0, 0, 0);
        Vector3d delta = new Vector3d(0, 0, 0);
        double particleSpeed = 0f;
        int count = 0;
        int particleLifeTime = 20;

        int size = arguments.size();
        if (size > 1) {
            pos.x = arguments.getAsDouble(context, 1);
        }
        if (size > 2) {
            pos.y = arguments.getAsDouble(context, 2);
        }
        if (size > 3) {
            pos.z = arguments.getAsDouble(context, 3);
        }
        if (size > 4) {
            delta.x = arguments.getAsDouble(context, 4);
        }
        if (size > 5) {
            delta.y = arguments.getAsDouble(context, 5);
        }
        if (size > 6) {
            delta.z = arguments.getAsDouble(context, 6);
        }
        if (size > 7) {
            particleSpeed = arguments.getAsDouble(context, 7);
        }
        if (size > 8) {
            count = Math.max(arguments.getAsInt(context, 8), 0);
        }
        if (size > 9) {
            particleLifeTime = Math.max(arguments.getAsInt(context, 9), 1);
        }
        spawnParticle(context.entity(), id, pos, delta, particleSpeed, count, particleLifeTime, absPos, context.entity().random());
        return true;
    }

    private static void spawnParticle(IContext<Entity> context, String id, Vector3d pos, Vector3d delta,
                                       double particleSpeed, int count, int particleLifeTime, boolean absPos, Random random) {
        ParticleOptions cachedOptions = PARTICLE_OPTIONS_CACHE.getIfPresent(id);
        if (cachedOptions == null) {
            cachedOptions = parse(id, context);
            PARTICLE_OPTIONS_CACHE.put(id, cachedOptions);
        }
        if (cachedOptions.id() == -1) {
            return;
        }
        Entity entity = context.entity();
        ParticleOptions particleOptions = cachedOptions;
        ParticleManager particleEngine = Minecraft.getMinecraft().effectRenderer;
        if (count == 0) {
            // 单个粒子准确在指定位置生成
            Vec3d offset = new Vec3d(pos.x, pos.y, pos.z);
            if (!absPos) {
                if (entity instanceof EntityPlayer player) {
                    offset = offset.rotateYaw(-MathUtil.degreesToRadians(player.renderYawOffset));
                } else {
                    offset = offset.rotateYaw(-MathUtil.degreesToRadians(entity.rotationYaw));
                }
            }

            double xPos = entity.posX + offset.x;
            double yPos = entity.posY + offset.y;
            double zPos = entity.posZ + offset.z;

            double xSpeed = particleSpeed * delta.x;
            double ySpeed = particleSpeed * delta.y;
            double zSpeed = particleSpeed * delta.z;

            Minecraft.getMinecraft().addScheduledTask(() -> {
                Particle result = particleEngine.spawnEffectParticle(particleOptions.id(), xPos, yPos, zPos, xSpeed, ySpeed, zSpeed, particleOptions.parameters());
                if (result != null) {
                    result.setMaxAge(particleLifeTime);
                }
            });
        } else {
            // 多个粒子就需要加点随机了
            for (int i = 0; i < count; ++i) {
                createParticle(entity, pos, delta, particleSpeed, particleLifeTime, particleEngine, particleOptions, absPos, random);
            }
        }
    }

    private static void createParticle(Entity entity, Vector3d pos, Vector3d delta, double particleSpeed, int particleLifeTime,
                                       ParticleManager particleEngine, ParticleOptions particleOptions, boolean absPos, Random random) {
        double offsetX = random.nextGaussian() * delta.x;
        double offsetY = random.nextGaussian() * delta.y;
        double offsetZ = random.nextGaussian() * delta.z;

        double xSpeed = random.nextGaussian() * particleSpeed;
        double ySpeed = random.nextGaussian() * particleSpeed;
        double zSpeed = random.nextGaussian() * particleSpeed;

        Vec3d offset = new Vec3d(pos.x + offsetX, pos.y + offsetY, pos.z + offsetZ);
        if (!absPos) {
            offset = offset.rotateYaw(-entity.rotationYaw * (float) (Math.PI / 180.0D));
        }

        double posX = entity.posX + offset.x;
        double posY = entity.posY + offset.y;
        double posZ = entity.posZ + offset.z;

        Minecraft.getMinecraft().addScheduledTask(() -> {
            Particle result = particleEngine.spawnEffectParticle(particleOptions.id(), posX, posY, posZ, xSpeed, ySpeed, zSpeed, particleOptions.parameters());
            if (result != null) {
                result.setMaxAge(particleLifeTime);
            }
        });
    }

    // TODO: 允许注册粒子
    private static ParticleOptions parse(String id, IContext<?> context) {
        String[] tokens = StringUtils.split(id);
        if (tokens == null || tokens.length == 0) {
            context.debugPrint("Particle id is empty in '%s'.", id);
            return ParticleOptions.INVALID;
        }

        String[] location = ResourceLocation.splitObjectName(tokens[0]);
        // 忽略 namespace
        EnumParticleTypes type = EnumParticleTypes.getByName(location[1]);
        if (type == null) {
            context.debugPrint("Particle '%s' is not registered.", tokens[0]);
            return ParticleOptions.INVALID;
        }
        if (tokens.length - 1 != type.getArgumentCount()) {
            context.debugPrint("Particle '%s' (%s) expects %d parameter(s) but received %d.",
                    tokens[0], type.getParticleName(), type.getArgumentCount(), tokens.length - 1);
            return ParticleOptions.INVALID;
        }

        int[] params = new int[type.getArgumentCount()];
        for (int i = 0; i < params.length; ++i) {
            String param = tokens[i + 1];
            try {
                params[i] = Integer.parseInt(param);
            } catch (NumberFormatException ignored) {
                context.debugPrint("Particle '%s' (%s) has invalid parameter #%d '%s'; expected an integer.",
                        tokens[0], type.getParticleName(), i + 1, param);
                return ParticleOptions.INVALID;
            }
        }
        return new ParticleOptions(type.getParticleID(), params);
    }

    private record ParticleOptions(int id, int[] parameters) {
        private static final ParticleOptions INVALID = new ParticleOptions(-1, null);
    }
}
