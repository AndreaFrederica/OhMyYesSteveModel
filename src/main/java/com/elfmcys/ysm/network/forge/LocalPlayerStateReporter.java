package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.geckolib3.core.molang.util.StringPool;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.protocol.NegotiatedSessionPolicy;
import com.elfmcys.ysm.network.protocol.PlayerStateReportPlanner;
import com.elfmcys.ysm.network.protocol.PlayerStateSection;
import com.elfmcys.ysm.network.protocol.ProtocolLimits;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.entity.EntityPlayerSP;

final class LocalPlayerStateReporter {
    private final PlayerStateReportPlanner planner = new PlayerStateReportPlanner();
    private ClientSessionRuntime.BusinessSession boundSession;
    private NegotiatedSessionPolicy policy;

    synchronized void setAnimation(String animationId) {
        planner.setAnimation(animationId);
    }

    synchronized void restartReportLifetime() {
        if (bindCurrentSession()) {
            planner.restartReportLifetime();
        }
    }

    synchronized void acceptAuthoritativeFull(Hash256 modelHash, Integer roamingKey) {
        if (bindCurrentSession()) {
            planner.acceptAuthority(modelHash, roamingKey);
        }
    }

    synchronized void tick(EntityPlayerSP player, PlayerAnimatableCapability capability) {
        if (!bindCurrentSession()) {
            return;
        }
        planner.observe(System.nanoTime(), player.getEntityId(), roamingSample(capability))
                .ifPresent(report -> {
                    try {
                        NetworkHandler.sendToServer(report);
                    } catch (RuntimeException ignored) {
                    }
                });
    }

    /**
     * Samples this producer's roaming under the authority's namespace. A self FULL that omitted
     * roaming leaves the namespace to the applied model, and while neither is known the opportunity
     * still forms for the independent animation section.
     */
    private PlayerStateReportPlanner.RoamingSample roamingSample(
            PlayerAnimatableCapability capability) {
        if (!policy.stateReportPolicy().requestedSections().contains(PlayerStateSection.ROAMING)) {
            return PlayerStateReportPlanner.RoamingSample.absent();
        }
        var namespace = planner.authoritativeRoamingKey();
        if (namespace == null) {
            namespace = capability.localRoamingKey();
        }
        if (namespace == null) {
            return PlayerStateReportPlanner.RoamingSample.absent();
        }
        var snapshot = capability.roamingSnapshot(namespace);
        return new PlayerStateReportPlanner.RoamingSample(
                snapshot.modelKey(), roaming(snapshot.values()));
    }

    synchronized void resetSession() {
        if (boundSession == null && policy == null) {
            return;
        }
        boundSession = null;
        policy = null;
        planner.endSession();
    }

    private boolean bindCurrentSession() {
        var currentSession = ClientSessionRuntime.businessSession().orElse(null);
        if (currentSession == null) {
            resetSession();
            return false;
        }
        if (boundSession == currentSession) {
            return true;
        }
        boundSession = currentSession;
        policy = currentSession.policy();
        planner.beginSession(policy.stateReportPolicy());
        return true;
    }

    private static Map<String, Float> roaming(Int2FloatMap values) {
        if (values.size() > ProtocolLimits.MAX_ROAMING_VARIABLES) {
            throw new IllegalArgumentException("roaming field count exceeds wire capacity");
        }
        var result = new HashMap<String, Float>();
        for (var entry : values.int2FloatEntrySet()) {
            var name = StringPool.getString(entry.getIntKey());
            if (name == null || name.isBlank()
                    || name.getBytes(StandardCharsets.UTF_8).length
                    > ProtocolLimits.MAX_ROAMING_VARIABLE_NAME_BYTES
                    || !Float.isFinite(entry.getFloatValue())) {
                throw new IllegalArgumentException("invalid roaming field for wire delivery");
            }
            result.put(name, entry.getFloatValue());
        }
        return Map.copyOf(result);
    }
}
