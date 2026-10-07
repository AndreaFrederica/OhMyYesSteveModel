package com.elfmcys.ysm.client;

import com.elfmcys.ysm.CommonProxy;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.*;
import com.elfmcys.ysm.client.animation.AnimationRegister;
import com.elfmcys.ysm.client.command.ClientRootCommand;
import com.elfmcys.ysm.client.compat.FirstPersonCompat;
import com.elfmcys.ysm.client.compat.OptifineCompat;
import com.elfmcys.ysm.client.compat.backport.BackportCompat;
import com.elfmcys.ysm.client.compat.carryon.CarryOnCompat;
import com.elfmcys.ysm.client.compat.immersivemelodies.ImmersiveMelodiesCompat;
import com.elfmcys.ysm.client.compat.slashblade.SlashBladeCompat;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.client.gui.DownloadScreen;
import com.elfmcys.ysm.client.gui.PlayerModelScreen;
import com.elfmcys.ysm.client.input.*;
import com.elfmcys.ysm.client.model.locator.FirstPersonLocator;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.client.model.locator.ProjectileLocator;
import com.elfmcys.ysm.client.model.locator.VehicleLocator;
import com.elfmcys.ysm.model.service.ClientModelService;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLInterModComms;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

@SuppressWarnings("unused")
public class ClientProxy extends CommonProxy {
    private static final String DOWNLOAD_SCREEN_METHOD = "DownloadScreen";
    private static @Nullable GuiScreen DOWNLOAD_SCREEN;

    @Override
    protected void registerCapability() {
        super.registerCapability();

        CapabilityManager.INSTANCE.register(PlayerAnimatableCapability.class, new EmptyCapabilityStorage<>(), () -> null);
        CapabilityManager.INSTANCE.register(ProjectileAnimatableCapability.class, new EmptyCapabilityStorage<>(), () -> null);
        CapabilityManager.INSTANCE.register(VehicleAnimatableCapability.class, new EmptyCapabilityStorage<>(), () -> null);
        CapabilityManager.INSTANCE.register(ClientLazyCapability.class, new EmptyCapabilityStorage<>(), () -> null);
    }

    @Override
    protected void init(FMLInitializationEvent event) {
        super.init(event);
        ClientRegistry.registerKeyBinding(PlayerModelScreenKey.PLAYER_MODEL_KEY);

        if (!YesSteveModel.isAvailable()) {
            return;
        }

        ClientRegistry.registerKeyBinding(AnimationRouletteKey.ANIMATION_ROULETTE_KEY);
        ClientRegistry.registerKeyBinding(AnimationRouletteKey.LOCK_ROULETTE_KEY);
        ClientRegistry.registerKeyBinding(DebugAnimationKey.DEBUG_ANIMATION_KEY);
        ClientRegistry.registerKeyBinding(ExtraPlayerConfigKey.EXTRA_PLAYER_RENDER_KEY);
        ExtraAnimationKey.registerKeyBinding();

        ClientCommandHandler.instance.registerCommand(new ClientRootCommand());

        AnimationRegister.registerAnimationState();
        RegisterEntityRenderersEvent.registerReloadListener();

        BackportCompat.init();
        /*
        CuriosCompat.init();
        */
        FirstPersonCompat.init();
        /*
        RealCameraCompat.init();
        PlayerAnimatorCompat.init();
        BetterCombatCompat.init();
        ARCompat.init();
        */
        OptifineCompat.init();
        /*
        CosmeticArmorCompat.init();
        ElytraSlotCompat.init();
        TACZCompat.init();
        SWarfareCompat.init();
        TlmClientCompat.init();
        */
        CarryOnCompat.init();
        /*
        ParCoolCompat.init();
        */
        SlashBladeCompat.init();
        /*
        SwemCompat.init();
        CreateCompat.init();
        SophisticatedCompat.init();
        SimpleHatsCompat.init();
        */
        ImmersiveMelodiesCompat.init();
        /*
        IronsSpellBooksCompat.init();
        SimplePlaneCompat.init();
        ImmersiveAircraftCompat.init();
        */

        // Model render target data is now owned by the Java model service.
        PlayerLocator.init();
        FirstPersonLocator.init();
        ProjectileLocator.init();
        VehicleLocator.init();

        ClientModelService.start();
    }

    @Override
    protected void interModProcess(FMLInterModComms.IMCEvent event) {
        super.interModProcess(event);
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        for (var message : event.getMessages()) {
            if (DOWNLOAD_SCREEN_METHOD.equals(message.key)) {
                message.getFunctionValue(Void.class, GuiScreen.class).ifPresent(factory -> {
                    if (factory.apply(null) instanceof GuiScreen screen) {
                        DOWNLOAD_SCREEN = screen;
                    }
                });
            }
        }
    }

    public static void openDownloadScreen(PlayerModelScreen modelScreen) {
        modelScreen.mc.displayGuiScreen(Objects.requireNonNullElseGet(DOWNLOAD_SCREEN, () -> new DownloadScreen(modelScreen)));
    }
}
