package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.molang.CustomMolangParser;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.elfmcys.ysm.molang.parser.ParseException;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.config.GuiUtils;

import java.text.DecimalFormat;

public class FlatSlider extends Slider implements IConfigFormsButton {
    private static final ResourceLocation BUTTON_TEXTURE = new ResourceLocation(YesSteveModel.MOD_ID, "texture/roulette.png");
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.##");

    public FlatSlider(int x, int y, String prefix, double currentValue, AnimatableEntity<?> animatableEntity, String molang,
                      double step, double min, double max) {
        super(x, y, 115, 15, prefix, "", min, max, currentValue, step, 0, true,
                slider -> onUpdate(slider, animatableEntity, molang));
    }

    private static void onUpdate(Slider slider, AnimatableEntity<?> animatableEntity, String molang) {
        try {
            String molangExpress = molang + "=" + slider.getValue();
            IValue parsed = CustomMolangParser.parseSingleExpressionUnsafe(molangExpress);
            animatableEntity.executeMolangExp(parsed, true, false, null);
            if (!CustomMolangParser.hasOnlyRoamingAssignment(molangExpress) && NetworkHandler.isRemoteChannelPresent() && !ServerConfig.LOW_BANDWIDTH_USAGE) {
                // 同步到周围的玩家
                ClientProtocolGateway.submitRouletteExpression(animatableEntity.getEntity(), molangExpress);
            }
        } catch (ParseException exception) {
            YesSteveModel.LOGGER.error(exception);
        }
    }

    @Override
    public String getValueString() {
        return DECIMAL_FORMAT.format(this.getValue());
    }

    public void setDisplayedValue(double value) {
        // Projection refresh must not write the observed value back to Molang.
        this.setValue(value);
    }

    @Override
    protected void renderWidget(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        mc.getTextureManager().bindTexture(BUTTON_TEXTURE);
        GuiUtils.drawContinuousTexturedBox(this.x, this.y, 0, 24, this.width, this.height, 200, 15, 2, 3, 2, 2, this.zLevel);
        int handleTextureY = this.enabled && this.isMouseOver() ? 84 : 64;
        GuiUtils.drawContinuousTexturedBox(this.x + (int) (this.sliderValue * (this.width - 8)), this.y, 0, handleTextureY, 8, this.height, 200, 15, 2, 3, 2, 2, this.zLevel);
        this.renderScrollingString(mc.fontRenderer, 2, getFGColor());
    }
}
