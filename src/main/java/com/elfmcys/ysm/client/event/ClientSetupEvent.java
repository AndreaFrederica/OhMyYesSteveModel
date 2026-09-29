package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.AnimationRegister;
import com.elfmcys.ysm.client.compat.ARCompat;
import com.elfmcys.ysm.client.compat.CosmeticArmorCompat;
import com.elfmcys.ysm.client.compat.ElytraSlotCompat;
import com.elfmcys.ysm.client.compat.FirstPersonCompat;
import com.elfmcys.ysm.client.compat.ImmersiveAircraftCompat;
import com.elfmcys.ysm.client.compat.IrisCompat;
import com.elfmcys.ysm.client.compat.OptifineCompat;
import com.elfmcys.ysm.client.compat.PlayerAnimatorCompat;
import com.elfmcys.ysm.client.compat.SimplePlaneCompat;
import com.elfmcys.ysm.client.compat.backpack.sophisticated.SophisticatedCompat;
import com.elfmcys.ysm.client.compat.bettercombat.BetterCombatCompat;
import com.elfmcys.ysm.client.compat.carryon.CarryOnCompat;
import com.elfmcys.ysm.client.compat.create.CreateCompat;
import com.elfmcys.ysm.client.compat.curios.CuriosCompat;
import com.elfmcys.ysm.client.compat.immersivemelodies.ImmersiveMelodiesCompat;
import com.elfmcys.ysm.client.compat.ironsspellbooks.IronsSpellBooksCompat;
import com.elfmcys.ysm.client.compat.parcool.ParCoolCompat;
import com.elfmcys.ysm.client.compat.realcamera.RealCameraCompat;
import com.elfmcys.ysm.client.compat.simplehat.SimpleHatsCompat;
import com.elfmcys.ysm.client.compat.slashblade.SlashBladeCompat;
import com.elfmcys.ysm.client.compat.swarfare.SWarfareCompat;
import com.elfmcys.ysm.client.compat.swem.SwemCompat;
import com.elfmcys.ysm.client.compat.tacz.TACZCompat;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.elfmcys.ysm.client.gui.overlay.DebugAnimationScreen;
import com.elfmcys.ysm.client.gui.overlay.ExtraPlayerScreen;
import com.elfmcys.ysm.client.gui.overlay.LoadingStateScreen;
import com.elfmcys.ysm.client.input.AnimationRouletteKey;
import com.elfmcys.ysm.client.input.DebugAnimationKey;
import com.elfmcys.ysm.client.input.ExtraAnimationKey;
import com.elfmcys.ysm.client.input.ExtraPlayerConfigKey;
import com.elfmcys.ysm.client.input.PlayerModelScreenKey;
import com.elfmcys.ysm.client.model.locator.FirstPersonLocator;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.client.model.locator.ProjectileLocator;
import com.elfmcys.ysm.client.model.locator.VehicleLocator;
import com.elfmcys.ysm.config.ClientConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Optional;

import static net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.DEBUG_TEXT;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientSetupEvent {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }

        AnimationRegister.registerAnimationState();

        event.enqueueWork(() -> {
            CuriosCompat.init();
            FirstPersonCompat.init();
            RealCameraCompat.init();
            PlayerAnimatorCompat.init();
            BetterCombatCompat.init();
            IrisCompat.init();
            ARCompat.init();
            OptifineCompat.init();
            CosmeticArmorCompat.init();
            ElytraSlotCompat.init();
            TACZCompat.init();
            SWarfareCompat.init();
            TlmClientCompat.init();
            CarryOnCompat.init();
            ParCoolCompat.init();
            SlashBladeCompat.init();
            SwemCompat.init();
            CreateCompat.init();
            SophisticatedCompat.init();
            SimpleHatsCompat.init();
            ImmersiveMelodiesCompat.init();
            IronsSpellBooksCompat.init();
            SimplePlaneCompat.init();
            ImmersiveAircraftCompat.init();

            checkCompatibility(ParCoolCompat.getCompatibilityWarning());
            checkCompatibility(SophisticatedCompat.getCompatibilityWarning());
            if (ClientConfig.DISABLE_SELF_MODEL.get() &&
                    ClientConfig.DISABLE_OTHER_MODEL.get() &&
                    ClientConfig.DISABLE_SELF_HANDS.get()) {
                informIncompatible("epicfight", "Epic Fight");
            }

            // Model render target data is now owned by the Java model service.
            PlayerLocator.init();
            FirstPersonLocator.init();
            ProjectileLocator.init();
            VehicleLocator.init();

            ClientModelService.start();
        });
    }

    @SubscribeEvent
    public static void onLoadComplete(net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent event) {
        // @YsmEventHandler discovery runs during load-complete; enqueue after every mod's listeners.
        event.enqueueWork(com.elfmcys.ysm.client.renderer.RenderStateModifiers::init);
    }

    private static void checkCompatibility(Optional<Pair<String, String>> infoHolder) {
        infoHolder.ifPresent(info -> {
            ModLoader.get().addWarning(new ModLoadingWarning(
                    LoadingModList.get().getModFileById(YesSteveModel.MOD_ID).getMods().get(0), ModLoadingStage.SIDED_SETUP,
                    "error.yes_steve_model.incompatible_mod_version", info.getKey(), info.getValue()));
        });
    }

    private static void informIncompatible(String modId, String modName) {
        if (LoadingModList.get().getModFileById(modId) != null) {
            ModLoader.get().addWarning(new ModLoadingWarning(
                    LoadingModList.get().getModFileById(YesSteveModel.MOD_ID).getMods().get(0), ModLoadingStage.SIDED_SETUP,
                    "error.yes_steve_model.incompatible_mod", modName));
        }
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PlayerModelScreenKey.PLAYER_MODEL_KEY);

        if (!YesSteveModel.isAvailable()) {
            return;
        }

        event.register(AnimationRouletteKey.ANIMATION_ROULETTE_KEY);
        event.register(AnimationRouletteKey.LOCK_ROULETTE_KEY);
        event.register(DebugAnimationKey.DEBUG_ANIMATION_KEY);
        event.register(ExtraPlayerConfigKey.EXTRA_PLAYER_RENDER_KEY);
        ExtraAnimationKey.registerKeyBinding(event);
    }

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        event.registerAbove(DEBUG_TEXT.id(), "ysm_debug_info", DebugAnimationScreen.getGuiOverlay());
        event.registerAbove(DEBUG_TEXT.id(), "ysm_extra_player", new ExtraPlayerScreen());
        event.registerAbove(DEBUG_TEXT.id(), "ysm_loading_state", new LoadingStateScreen());
    }

}
