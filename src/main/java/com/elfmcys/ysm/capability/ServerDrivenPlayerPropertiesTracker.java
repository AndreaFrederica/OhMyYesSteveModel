package com.elfmcys.ysm.capability;

import com.elfmcys.ysm.event.LivingShieldBlockEvent;
import com.elfmcys.ysm.network.forge.PlayerStateHandler;
import com.elfmcys.ysm.proto.network.AnimationState;
import com.elfmcys.ysm.proto.network.EffectState;
import com.elfmcys.ysm.proto.network.EffectStateSet;
import com.elfmcys.ysm.proto.network.GameplayState;
import com.elfmcys.ysm.proto.network.MolangVariable;
import com.elfmcys.ysm.proto.network.PlayerStateUpdate;
import com.elfmcys.ysm.proto.network.RoamingState;
import com.elfmcys.ysm.util.TokenBucket;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.potion.Potion;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.commons.lang3.StringUtils;
import org.joml.Math;

public final class ServerDrivenPlayerPropertiesTracker {
    private TokenBucket rateLimiter;
    private boolean lowBandwidth;
    private GameplayState pendingGameplay =
            GameplayState.newBuilder().build();
    private final Object2IntOpenHashMap<Potion> pendingEffects = new Object2IntOpenHashMap<>();
    private AnimationState pendingAnimation;
    private int pendingRoamingKey;
    private final Object2FloatOpenHashMap<String> pendingRoaming = new Object2FloatOpenHashMap<>();

    private int expLevel = -1;
    private boolean fly;
    private int health = -1;
    private int maxHealth = -1;
    private int foodLevel = -1;
    private float xxa;
    private float yya;
    private float zza;
    private boolean inShieldBlockCooldown;
    private String extraAnimation = "";

    public ServerDrivenPlayerPropertiesTracker() {
        setLowBandwidth(false);
    }

    public void setLowBandwidth(boolean value) {
        if (value != lowBandwidth || rateLimiter == null) {
            lowBandwidth = value;
            rateLimiter = value ? new TokenBucket(3, 3) : new TokenBucket(4, 7);
        }
    }

    public void tick(EntityPlayerMP player, boolean sync, boolean lowBandwidthUsage) {
        setLowBandwidth(lowBandwidthUsage);
        if (!sync) {
            clearPending();
        }
        if (expLevel != player.experienceLevel) {
            expLevel = player.experienceLevel;
            if (sync) pendingGameplay = pendingGameplay.withExperienceLevel(expLevel);
        }
        if (fly != player.capabilities.isFlying) {
            fly = player.capabilities.isFlying;
            if (sync) pendingGameplay = pendingGameplay.withFlying(fly);
        }
        if (health != (int) player.getHealth()) {
            health = (int) player.getHealth();
            if (sync) pendingGameplay = pendingGameplay.withHealth(health);
        }
        if (maxHealth != (int) player.getMaxHealth()) {
            maxHealth = (int) player.getMaxHealth();
            if (sync) pendingGameplay = pendingGameplay.withMaxHealth(maxHealth);
        }
        if (foodLevel != player.getFoodStats().getFoodLevel()) {
            foodLevel = player.getFoodStats().getFoodLevel();
            if (sync) pendingGameplay = pendingGameplay.withFoodLevel(foodLevel);
        }
        if (xxa != player.moveStrafing) {
            xxa = player.moveStrafing;
            if (sync) pendingGameplay = pendingGameplay.withMoveXQ7(quantizeAxis(xxa));
        }
        if (yya != player.moveVertical) {
            yya = player.moveVertical;
            if (sync) pendingGameplay = pendingGameplay.withMoveYQ7(quantizeAxis(yya));
        }
        if (zza != player.moveForward) {
            zza = player.moveForward;
            if (sync) pendingGameplay = pendingGameplay.withMoveZQ7(quantizeAxis(zza));
        }
        var cooldown = LivingShieldBlockEvent.inShieldBlockCooldown(player);
        if (inShieldBlockCooldown != cooldown) {
            inShieldBlockCooldown = cooldown;
            if (sync) pendingGameplay = pendingGameplay.withShieldCooldown(cooldown);
        }
        if (sync) {
            broadcastPending(player);
        }
    }

    public void addEffect(EntityPlayerMP player, Potion effect, int level) {
        pendingEffects.put(effect, level);
        broadcastPending(player);
    }

    public void removeEffect(EntityPlayerMP player, Potion effect) {
        pendingEffects.put(effect, 0);
        broadcastPending(player);
    }

    public void setExtraAnimation(EntityPlayerMP player, boolean sync, String animation) {
        if (StringUtils.isEmpty(animation) && StringUtils.isEmpty(extraAnimation)) {
            return;
        }
        extraAnimation = animation;
        if (sync) {
            pendingAnimation = (animation.isEmpty()
                    ? AnimationState.newBuilder()
                            .setStopped(true)
                    : AnimationState.newBuilder()
                            .setAnimationId(animation)).build();
            broadcastPending(player);
        } else {
            pendingAnimation = null;
        }
    }

    public void acceptClientAnimation(String animation) {
        extraAnimation = animation;
        pendingAnimation = null;
    }

    public void updateMolangVars(EntityPlayerMP player, boolean sync, int modelKey,
                                 Object2FloatMap<String> variables) {
        if (lowBandwidth || !sync) {
            return;
        }
        if (pendingRoamingKey != modelKey) {
            pendingRoamingKey = modelKey;
            pendingRoaming.clear();
        }
        pendingRoaming.putAll(variables);
        broadcastPending(player);
    }

    public void acceptClientRoaming() {
        pendingRoaming.clear();
        pendingRoamingKey = 0;
    }

    public void populateFull(
            PlayerStateUpdate.Builder update,
            EntityPlayerMP player) {
        var gameplay = GameplayState.newBuilder()
                .setFlying(player.capabilities.isFlying)
                .setExperienceLevel(player.experienceLevel)
                .setFoodLevel(player.getFoodStats().getFoodLevel())
                .setHealth((int) player.getHealth())
                .setMaxHealth((int) player.getMaxHealth())
                .setMoveXQ7(quantizeAxis(player.moveStrafing))
                .setMoveYQ7(quantizeAxis(player.moveVertical))
                .setMoveZQ7(quantizeAxis(player.moveForward))
                .setShieldCooldown(LivingShieldBlockEvent.inShieldBlockCooldown(player))
                .build();
        update.setGameplay(gameplay);

        var effects = EffectStateSet.newBuilder();
        for (var instance : player.getActivePotionEffects()) {
            var key = ForgeRegistries.POTIONS.getKey(instance.getPotion());
            if (key != null) {
                effects.addEffects(EffectState.newBuilder()
                        .setEffectId(key.toString())
                        .setLevel(instance.getAmplifier() + 1)
                        .build());
            }
        }
        update.setEffects(effects.build());
        update.setAnimation(extraAnimation.isEmpty()
                ? AnimationState.newBuilder()
                        .setStopped(true).build()
                : AnimationState.newBuilder()
                        .setAnimationId(extraAnimation).build());
    }

    private void broadcastPending(EntityPlayerMP player) {
        if (!hasPending()) {
            return;
        }
        var update = PlayerStateHandler.newDelta(player.getEntityId());
        if (pendingGameplay.getSerializedSize() != 0) {
            update.setGameplay(pendingGameplay);
        }
        if (!pendingEffects.isEmpty()) {
            var effects = EffectStateSet.newBuilder();
            pendingEffects.object2IntEntrySet().fastForEach(entry -> {
                var key = ForgeRegistries.POTIONS.getKey(entry.getKey());
                if (key != null) {
                    effects.addEffects(EffectState.newBuilder()
                            .setEffectId(key.toString())
                            .setLevel(entry.getIntValue())
                            .build());
                }
            });
            update.setEffects(effects.build());
        }
        if (pendingAnimation != null) {
            update.setAnimation(pendingAnimation);
        }
        if (!pendingRoaming.isEmpty()) {
            var roaming = RoamingState.newBuilder()
                    .setModelKey(pendingRoamingKey);
            pendingRoaming.object2FloatEntrySet().fastForEach(entry -> roaming.addVariables(
                    MolangVariable.newBuilder()
                            .setName(entry.getKey())
                            .setValue(entry.getFloatValue())
                            .build()));
            update.setRoaming(roaming.build());
        }
        var message = update.build();
        clearPending();
        if (rateLimiter.request()) {
            PlayerStateHandler.broadcast(player, message);
        }
    }

    private boolean hasPending() {
        return pendingGameplay.getSerializedSize() != 0 || !pendingEffects.isEmpty()
                || pendingAnimation != null || !pendingRoaming.isEmpty();
    }

    private void clearPending() {
        pendingGameplay = com.elfmcys.ysm.proto.network.GameplayState
                .newBuilder().build();
        pendingEffects.clear();
        pendingAnimation = null;
        pendingRoaming.clear();
        pendingRoamingKey = 0;
    }

    private static int quantizeAxis(float value) {
        return Math.round(Math.clamp(value, -1f, 1f) * 127f);
    }
}
