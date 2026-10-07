package com.elfmcys.ysm.config;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.config.util.ConfigUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Set;

@SuppressWarnings("unused")
@SideOnly(Side.CLIENT)
public class ConfigGuiFactory implements IModGuiFactory {
    @Override
    public void initialize(Minecraft mc) {
    }

    @Override
    public boolean hasConfigGui() {
        return true;
    }

    @Override
    public GuiScreen createConfigGui(GuiScreen parent) {
        return new GuiConfig(
                parent,
                ConfigUtil.getRootElements(Config.getConfig()),
                YesSteveModel.MOD_ID,
                false,
                false,
                YesSteveModel.MOD_NAME
        );
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return Set.of();
    }
}
