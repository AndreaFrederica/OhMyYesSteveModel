package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

@SideOnly(Side.CLIENT)
public class FlatCheckbox extends Button implements IConfigFormsButton {
    private static final ResourceLocation BUTTON_TEXTURE = new ResourceLocation(YesSteveModel.MOD_ID, "texture/roulette.png");
    private boolean selected;

    public FlatCheckbox(int xIn, int yIn, int width, String name, Consumer<Button> onClick) {
        super(xIn, yIn, width, 12, name, onClick);
    }

    public FlatCheckbox(int xIn, int yIn, String name, Consumer<Button> onClick) {
        this(xIn, yIn, 115, name, onClick);
    }

    @Override
    protected void renderWidget(@NotNull Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        mc.getTextureManager().bindTexture(BUTTON_TEXTURE);
        this.drawTexturedModalRect(
                this.x, this.y,
                this.selected ? 128 : 0,
                this.isMouseOver() ? 12 : 0,
                this.width, this.height
        );
        mc.fontRenderer.drawString(this.getMessage(), this.x + 14, this.y + 2, 0xffffffff, false);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void onPress() {
        this.selected = !this.selected;
        super.onPress();
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean selected() {
        return this.selected;
    }
}
