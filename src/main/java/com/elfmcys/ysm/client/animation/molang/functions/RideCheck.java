package com.elfmcys.ysm.client.animation.molang.functions;

import com.elfmcys.ysm.client.match.EntityMatchKind;
import com.elfmcys.ysm.client.match.EntityMatchRegistry;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.function.entity.LivingEntityFunction;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.apache.commons.lang3.StringUtils;

public class RideCheck extends LivingEntityFunction {
    private static final String VEHICLE = "vehicle";
    private static final String PASSENGER = "passenger";
    private static final int FALSE = 0;
    private static final int TRUE = 1;

    public static RideCheck rideCheck() {
        return new RideCheck();
    }

    @Override
    protected Object eval(ExecutionContext<IContext<EntityLivingBase>> context, ArgumentCollection arguments) {
        String type = arguments.getAsString(context, 0);
        String input = arguments.getAsString(context, 1);
        EntityLivingBase entity = context.entity().entity();

        if (StringUtils.isBlank(input)) {
            return FALSE;
        }

        Entity checkEntity;
        if (VEHICLE.equals(type)) {
            checkEntity = entity.getRidingEntity();
        } else if (PASSENGER.equals(type)) {
            checkEntity = entity.getControllingPassenger();
        } else {
            return FALSE;
        }

        if (checkEntity == null || !checkEntity.isEntityAlive()) {
            return FALSE;
        }

        EntityMatchKind kind = EntityMatchKind.fromName(input);
        return EntityMatchRegistry.matches(entity, input, kind) ? TRUE : FALSE;
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 2 || size == 3;
    }
}
