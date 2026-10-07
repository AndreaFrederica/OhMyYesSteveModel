package com.elfmcys.ysm.api.internal.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.annotation.YsmEventHandler;
import net.minecraftforge.fml.common.discovery.ASMDataTable;
import net.minecraftforge.fml.common.eventhandler.EventBus;

import java.util.Collection;
import java.util.List;

/** Internal Forge discovery and registration bridge for {@link YsmEventHandler}. */
public final class YsmEventHandlerLoader {
    private static final String ANNOTATION_NAME = YsmEventHandler.class.getName();

    private YsmEventHandlerLoader() {
    }

    public static void attach(ASMDataTable asmData, EventBus eventBus) {
        registerAll(asmData, eventBus);
    }

    private static void registerAll(ASMDataTable asmData, EventBus eventBus) {
        List<String> handlerNames;
        try {
            handlerNames = discoverHandlerNames(asmData.getAll(ANNOTATION_NAME));
        } catch (RuntimeException | LinkageError exception) {
            YesSteveModel.LOGGER.error("Failed to discover @YsmEventHandler classes", exception);
            return;
        }

        ClassLoader loader = YsmEventHandlerLoader.class.getClassLoader();
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }
        for (String handlerName : handlerNames) {
            registerOne(eventBus, handlerName, loader);
        }
    }

    static List<String> discoverHandlerNames(Collection<ASMDataTable.ASMData> scanData) {
        return scanData.stream()
                .filter(data -> data.getObjectName() == null || data.getObjectName().equals(data.getClassName()))
                .map(ASMDataTable.ASMData::getClassName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static void registerOne(
            EventBus eventBus,
            String handlerName,
            ClassLoader loader) {
        YsmEventHandlerRegistration.Outcome outcome;
        try {
            outcome = YsmEventHandlerRegistration.prepare(handlerName, loader);
        } catch (YsmEventHandlerRegistration.Failure failure) {
            if (failure.stage() == YsmEventHandlerRegistration.Stage.CHECKER_MISSING) {
                YesSteveModel.LOGGER.error(
                        "Skipping @YsmEventHandler {} because checker {} is missing. "
                                + "Add the classifierless YSM JAR to annotationProcessor.",
                        handlerName, failure.subject());
            } else {
                YesSteveModel.LOGGER.error(
                        "Skipping @YsmEventHandler {}: {} ({})",
                        handlerName, failure.getMessage(), failure.subject(), failure.getCause());
            }
            return;
        }

        if (!outcome.shouldRegister()) {
            YesSteveModel.LOGGER.warn(
                    "Skipping @YsmEventHandler {}: status={}, coverageComplete={}, issues={}",
                    handlerName, outcome.status(), outcome.coverageComplete(), outcome.issues());
            return;
        }
        if (outcome.hasWarnings()) {
            YesSteveModel.LOGGER.warn(
                    "Registering @YsmEventHandler {} with compatibility warnings: "
                            + "status={}, coverageComplete={}, issues={}",
                    handlerName, outcome.status(), outcome.coverageComplete(), outcome.issues());
        }

        try {
            eventBus.register(outcome.handler());
            YesSteveModel.LOGGER.info("Registered @YsmEventHandler {}", handlerName);
        } catch (RuntimeException | LinkageError exception) {
            YesSteveModel.LOGGER.error(
                    "Failed to register @YsmEventHandler {}; continuing without it",
                    handlerName, exception);
        }
    }
}
