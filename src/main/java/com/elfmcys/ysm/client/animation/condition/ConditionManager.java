package com.elfmcys.ysm.client.animation.condition;

import net.minecraft.util.EnumHand;

public class ConditionManager {
    private final ConditionalSwing swing = new ConditionalSwing(EnumHand.MAIN_HAND);
    private final ConditionalSwing swingOffhand = new ConditionalSwing(EnumHand.OFF_HAND);
    private final ConditionalUse useMainHand = new ConditionalUse(EnumHand.MAIN_HAND);
    private final ConditionalUse useOffHand = new ConditionalUse(EnumHand.OFF_HAND);
    private final ConditionalHold holdMainHand = new ConditionalHold(EnumHand.MAIN_HAND);
    private final ConditionalHold holdOffHand = new ConditionalHold(EnumHand.OFF_HAND);
    private final ConditionArmor armor = new ConditionArmor();
    /*
    private final ConditionTAC tac = new ConditionTAC();
    */
    private final ConditionalVehicle vehicle = new ConditionalVehicle();
    private final ConditionalPassenger passenger = new ConditionalPassenger();
    private final ConditionalChair chair = new ConditionalChair();

    public void addTest(String name) {
        swing.addTest(name);
        swingOffhand.addTest(name);
        useMainHand.addTest(name);
        useOffHand.addTest(name);
        holdMainHand.addTest(name);
        holdOffHand.addTest(name);
        armor.addTest(name);
        /*
        tac.addTest(name);
        */
        vehicle.addTest(name);
        passenger.addTest(name);
        chair.addTest(name);
    }

    public ConditionalSwing getSwingMainhand() {
        return swing;
    }

    public ConditionalSwing getSwingOffhand() {
        return swingOffhand;
    }

    public ConditionalUse getUseMainhand() {
        return useMainHand;
    }

    public ConditionalUse getUseOffhand() {
        return useOffHand;
    }

    public ConditionalHold getHoldMainhand() {
        return holdMainHand;
    }

    public ConditionalHold getHoldOffhand() {
        return holdOffHand;
    }

    public ConditionArmor getArmor() {
        return armor;
    }

    /*
    public ConditionTAC getTAC() {
        return tac;
    }
    */

    public ConditionalVehicle getVehicle() {
        return vehicle;
    }

    public ConditionalPassenger getPassenger() {
        return passenger;
    }

    public ConditionalChair getChair() {
        return chair;
    }
}
