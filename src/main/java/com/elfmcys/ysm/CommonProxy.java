package com.elfmcys.ysm;

import com.elfmcys.ysm.api.internal.event.YsmEventHandlerLoader;
import com.elfmcys.ysm.capability.*;
import com.elfmcys.ysm.config.Config;
import com.elfmcys.ysm.command.RootCommand;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.util.NativeLibUtil;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.event.*;

@SuppressWarnings("unused")
public class CommonProxy {
    protected void preInit(FMLPreInitializationEvent event) {
        YesSteveModel.MOD = Loader.instance().activeModContainer();
        Config.init(event.getSuggestedConfigurationFile(), event.getSide());
        FMLInterModComms.sendFunctionMessage("theoneprobe", "getTheOneProbe", "com.elfmcys.ysm.client.compat.top.TopPlugin");

        try {
            NativeLibUtil.load();
        } catch (Exception e) {
            YesSteveModel.LOGGER.error("Failed to load ysm native library", e);
        }

        if (!NativeLibUtil.isAvailable()) {
            YesSteveModel.LOGGER.error(YesSteveModel.getUnavailableMessageString());
        }

        if (!YesSteveModel.isAvailable()) {
            return;
        }

        YsmEventHandlerLoader.attach(event.getAsmData(), YesSteveModel.EVENT_BUS);
        this.registerCapability();
    }

    protected void registerCapability() {
        CapabilityManager.INSTANCE.register(ModelInfoCapability.class, new ModelInfoCapability.Storage(), ModelInfoCapability::new);
        CapabilityManager.INSTANCE.register(ProjectileModelInfoCapability.class, new ProjectileModelInfoCapability.Storage(), ProjectileModelInfoCapability::new);
        CapabilityManager.INSTANCE.register(VehicleModelInfoCapability.class, new VehicleModelInfoCapability.Storage(), VehicleModelInfoCapability::new);
        CapabilityManager.INSTANCE.register(AuthModelsCapability.class, new AuthModelsCapability.Storage(), AuthModelsCapability::new);
        CapabilityManager.INSTANCE.register(StarModelsCapability.class, new StarModelsCapability.Storage(), StarModelsCapability::new);
    }

    protected void init(FMLInitializationEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        ModelRuntime.initialize();
        NetworkHandler.init();
    }

    protected void postInit(FMLPostInitializationEvent event) {
    }

    protected void serverStarting(FMLServerStartingEvent event) {
        if (!YesSteveModel.isAvailable()) {
            event.registerServerCommand(new RootCommand.Placeholder());
            return;
        }
        event.registerServerCommand(new RootCommand());
        ServerModelService.start(event.getServer());
    }

    protected void serverStopping(FMLServerStoppingEvent event) {
        ServerModelService.current().ifPresent(ServerModelService::close);
    }

    protected void interModProcess(FMLInterModComms.IMCEvent event) {
    }
}
