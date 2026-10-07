package com.elfmcys.ysm.api.model.v0.event;

import com.elfmcys.ysm.api.model.v0.ModelKind;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocator;
import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.function.Function;

public class RegisterModelLocatorEvent extends Event {
    private final ModelKind kind;
    private final Function<String, GeoLocator> registerFunction;

    // 缺失会报错
    public RegisterModelLocatorEvent() {
        kind = null;
        registerFunction = null;
    }

    public RegisterModelLocatorEvent(ModelKind kind, Function<String, GeoLocator> registerFunction) {
        this.kind = kind;
        this.registerFunction = registerFunction;
    }

    public ModelKind kind() {
        return kind;
    }

    public GeoLocator register(String locatorName) {
        return registerFunction.apply(locatorName);
    }
}
