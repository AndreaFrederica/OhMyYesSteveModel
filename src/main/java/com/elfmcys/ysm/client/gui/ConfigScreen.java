package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.client.gui.button.Button;
import com.elfmcys.ysm.client.gui.button.ConfigCheckBox;
import com.elfmcys.ysm.client.gui.button.FlatColorButton;
import com.elfmcys.ysm.client.gui.button.PositionButton;
import com.elfmcys.ysm.client.gui.button.Slider;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.config.Config;
import com.elfmcys.ysm.config.ExtraPlayerScreenConfig;
import com.elfmcys.ysm.config.LoadingStateScreenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ConfigScreen extends Screen {
    @Nullable
    private final PlayerModelScreen parent;

    public ConfigScreen(@Nullable PlayerModelScreen parent) {
        super();
        this.parent = parent;
    }

    @Override
    public void initGui() {
        int x = (width - 420) / 2;
        int y = (height - 265) / 2;

        addButton(new FlatColorButton(x + 5, y + 2, 80, 18, I18n.format("gui.yes_steve_model.model.return"), (b) -> this.mc.displayGuiScreen(parent)));

        addButton(new Slider(Button.ID, x + 5, y + 24, 320, 18, I18n.format("gui.yes_steve_model.config.sound_volume"),
                "%", 0, 100, ClientConfig.SOUND_VOLUME, true,
                slider -> ClientConfig.SOUND_VOLUME = slider.getValue()));

        addButton(new ConfigCheckBox(x + 5, y + 45, "disable_self_model", this.fontRenderer,
                ClientConfig.DISABLE_SELF_MODEL, value -> ClientConfig.DISABLE_SELF_MODEL = value));
        addButton(new ConfigCheckBox(x + 5, y + 67, "disable_other_model", this.fontRenderer,
                ClientConfig.DISABLE_OTHER_MODEL, value -> ClientConfig.DISABLE_OTHER_MODEL = value));
        addButton(new ConfigCheckBox(x + 5, y + 89, "print_animation_roulette_msg", this.fontRenderer,
                ClientConfig.PRINT_ANIMATION_ROULETTE_MSG, value -> ClientConfig.PRINT_ANIMATION_ROULETTE_MSG = value));
        addButton(new ConfigCheckBox(x + 5, y + 111, "disable_self_hands", this.fontRenderer,
                ClientConfig.DISABLE_SELF_HANDS, value -> ClientConfig.DISABLE_SELF_HANDS = value));
        addButton(new ConfigCheckBox(x + 5, y + 133, "disable_player_render", this.fontRenderer,
                ExtraPlayerScreenConfig.DISABLE_PLAYER_RENDER, value -> ExtraPlayerScreenConfig.DISABLE_PLAYER_RENDER = value));
        addButton(new ConfigCheckBox(x + 5, y + 155, "disable_projectile_model", this.fontRenderer,
                ClientConfig.DISABLE_PROJECTILE_MODEL, value -> ClientConfig.DISABLE_PROJECTILE_MODEL = value));
        addButton(new ConfigCheckBox(x + 5, y + 177, "disable_vehicle_model", this.fontRenderer,
                ClientConfig.DISABLE_VEHICLE_MODEL, value -> ClientConfig.DISABLE_VEHICLE_MODEL = value));
        addButton(new ConfigCheckBox(x + 5, y + 199, "disable_external_first_person_anim", this.fontRenderer,
                ClientConfig.DISABLE_EXTERNAL_FIRST_PERSON_ANIM, value -> ClientConfig.DISABLE_EXTERNAL_FIRST_PERSON_ANIM = value));
        addButton(new ConfigCheckBox(x + 5, y + 221, "disable_loading_state_screen", this.fontRenderer,
                LoadingStateScreenConfig.DISABLE_LOADING_STATE_SCREEN, value -> LoadingStateScreenConfig.DISABLE_LOADING_STATE_SCREEN = value));
        addButton(new ConfigCheckBox(x + 5, y + 243, "use_compatibility_renderer", this.fontRenderer,
                ClientConfig.USE_COMPATIBILITY_RENDERER, value -> ClientConfig.USE_COMPATIBILITY_RENDERER = value));

        addButton(new PositionButton(x + 5, y + 264));
    }

    @Override
    public void drawScreen(int pMouseX, int pMouseY, float pPartialTick) {
        drawDefaultBackground();
        super.drawScreen(pMouseX, pMouseY, pPartialTick);
    }

    @Override
    public void onGuiClosed() {
        Config.save();
        super.onGuiClosed();
    }

    @Override
    public void onResize(@NotNull Minecraft mc, int width, int height) {
        Config.save();
        super.onResize(mc, width, height);
    }
}
