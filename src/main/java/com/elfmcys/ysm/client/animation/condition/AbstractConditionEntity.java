package com.elfmcys.ysm.client.animation.condition;

import com.elfmcys.ysm.client.match.EntityMatchKind;
import com.elfmcys.ysm.client.match.EntityMatchRegistry;
import com.elfmcys.ysm.client.match.MatchConstants;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;

public abstract class AbstractConditionEntity {
    private final EnumMap<EntityMatchKind, ObjectOpenHashSet<String>> test = new EnumMap<>(EntityMatchKind.class);
    private final int preSize;
    private final String prefix;

    protected AbstractConditionEntity(String prefix) {
        this.preSize = prefix.length();
        this.prefix = prefix;
    }

    public void addTest(String name) {
        if (name.length() <= this.preSize || !name.startsWith(this.prefix)) {
            return;
        }
        String substring = name.substring(this.preSize);
        EntityMatchKind kind = EntityMatchKind.fromName(substring);
        if (kind == null) {
            return;
        }
        this.test.computeIfAbsent(kind, _ -> new ObjectOpenHashSet<>())
                .add(kind.normalize(substring));
    }

    @Nullable
    protected abstract Entity getEntity(EntityLivingBase livingEntity);

    public String doTest(EntityLivingBase livingEntity) {
        Entity entity = this.getEntity(livingEntity);
        if (entity == null || !entity.isEntityAlive()) {
            return MatchConstants.EMPTY;
        }
        for (EntityMatchKind kind : EntityMatchKind.values()) {
            String result = EntityMatchRegistry.test(entity, this.test.get(kind), kind);
            if (!result.isEmpty()) {
                return this.prefix + kind.prefix() + result;
            }
        }
        return MatchConstants.EMPTY;
    }
}
