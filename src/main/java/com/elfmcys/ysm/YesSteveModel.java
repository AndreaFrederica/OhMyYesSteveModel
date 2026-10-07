package com.elfmcys.ysm;

import com.elfmcys.ysm.util.NativeLibUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = YesSteveModel.MOD_ID,
        name = YesSteveModel.MOD_NAME,
        useMetadata = true,
        guiFactory = "com.elfmcys.ysm.config.ConfigGuiFactory"
)
public class YesSteveModel {
    public static final String MOD_ID = "ysm";
    public static final String MOD_NAME = "Yes Steve Model";
    public static ModContainer MOD;
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    static final EventBus EVENT_BUS = new EventBus();

    @SuppressWarnings("unused")
    @SidedProxy(
            serverSide = "com.elfmcys.ysm.CommonProxy",
            clientSide = "com.elfmcys.ysm.client.ClientProxy"
    )
    private static CommonProxy PROXY;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        PROXY.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        PROXY.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        PROXY.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        PROXY.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        PROXY.serverStopping(event);
    }

    @Mod.EventHandler
    public void interModProcess(FMLInterModComms.IMCEvent event) {
        PROXY.interModProcess(event);
    }

    public static void registerEventHandler(Object handler) {
        EVENT_BUS.register(handler);
    }

    public static boolean postEvent(Event event) {
        return EVENT_BUS.post(event);
    }

    public static boolean isAvailable() {
        return NativeLibUtil.isAvailable();
    }

    public static boolean isMobilePlatform() {
        return NativeLibUtil.isMobilePlatform();
    }

    @SideOnly(Side.CLIENT)
    public static void sendUnavailableMessage() {
        var player = Minecraft.getMinecraft().player;
        if (player != null) {
            player.sendMessage(getUnavailableMessage());
        }
    }

    /*
    public static ModLoadingWarning getUnavailableWarning() {
        return NativeLibUtil.getUnavailableWarning();
    }
    */

    public static ITextComponent getUnavailableMessage() {
        return NativeLibUtil.getUnsupportedMsg();
    }

    public static String getUnavailableMessageString() {
        return NativeLibUtil.getUnsupportedMsgStr();
    }
}
