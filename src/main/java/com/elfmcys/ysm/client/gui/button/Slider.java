package com.elfmcys.ysm.client.gui.button;

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.client.config.GuiUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.util.function.Consumer;

public class Slider extends Button {
    private final DecimalFormat format;
    private final String prefix;
    private final String suffix;
    private final double minValue;
    private final double maxValue;
    private final double stepSize;
    private final boolean drawString;
    private final @Nullable Consumer<Slider> onUpdate;
    private boolean dragging;
    protected double sliderValue;

    public Slider(int id, int x, int y, int width, int height,
                  String prefix, String suffix,
                  double minValue, double maxValue, double currentValue,
                  double stepSize, int precision, boolean drawString,
                  @Nullable Consumer<Slider> onUpdate) {
        super(id, x, y, width, height, "", _ -> {
        });
        this.prefix = prefix;
        this.suffix = suffix;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.stepSize = Math.abs(stepSize);
        this.sliderValue = this.snapToNearest(this.toSliderValue(currentValue));
        this.drawString = drawString;
        this.onUpdate = onUpdate;

        if (this.stepSize == 0D) {
            precision = Math.min(precision, 4);

            StringBuilder builder = new StringBuilder("0");
            if (precision > 0) {
                builder.append('.');
            }
            while (precision-- > 0) {
                builder.append('0');
            }
            this.format = new DecimalFormat(builder.toString());
        } else if (Math.abs(this.stepSize - Math.floor(this.stepSize)) < 1.0E-5D) {
            this.format = new DecimalFormat("0");
        } else {
            this.format = new DecimalFormat(Double.toString(this.stepSize).replaceAll("\\d", "0"));
        }
    }

    public Slider(int x, int y, int width, int height,
                  String prefix, String suffix,
                  double minValue, double maxValue, double currentValue,
                  double stepSize, int precision, boolean drawString,
                  @Nullable Consumer<Slider> onUpdate) {
        this(ID, x, y, width, height, prefix, suffix, minValue, maxValue, currentValue, stepSize, precision, drawString, onUpdate);
    }

    public Slider(int id, int x, int y, int width, int height,
                  String prefix, String suffix,
                  double minValue, double maxValue, double currentValue,
                  boolean drawString, @Nullable Consumer<Slider> onUpdate) {
        this(id, x, y, width, height, prefix, suffix, minValue, maxValue, currentValue, 1D, 0, drawString, onUpdate);
    }

    public Slider(int x, int y, int width, int height,
                  String prefix, String suffix,
                  double minValue, double maxValue, double currentValue,
                  boolean drawString, @Nullable Consumer<Slider> onUpdate) {
        this(ID, x, y, width, height, prefix, suffix, minValue, maxValue, currentValue, drawString, onUpdate);
    }

    public double getValue() {
        return this.sliderValue * (this.maxValue - this.minValue) + this.minValue;
    }

    public long getValueLong() {
        return Math.round(this.getValue());
    }

    public int getValueInt() {
        return (int) this.getValueLong();
    }

    public void setValue(double value) {
        this.sliderValue = this.snapToNearest(this.toSliderValue(value));
    }

    public String getValueString() {
        return this.format.format(this.getValue());
    }

    @Override
    protected void renderWidget(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        mc.getTextureManager().bindTexture(BUTTON_TEXTURES);
        // 背景
        GuiUtils.drawContinuousTexturedBox(this.x, this.y, 0, 46, this.width, this.height, 200, 20, 2, 3, 2, 2, this.zLevel);
        // 滑块
        int handleTextureY = this.enabled && this.isMouseOver() ? 66 + 20 : 66;
        GuiUtils.drawContinuousTexturedBox(this.x + (int) (this.sliderValue * (this.width - 8)), this.y, 0, handleTextureY, 8, this.height, 200, 20, 2, 3, 2, 2, this.zLevel);
        this.renderScrollingString(mc.fontRenderer, 2, this.getFGColor());
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!super.mousePressed(mc, mouseX, mouseY)) {
            return false;
        }

        this.setValueFromMouse(mouseX);
        this.dragging = true;
        return true;
    }

    @Override
    protected void mouseDragged(@NotNull Minecraft mc, int mouseX, int mouseY) {
        if (this.enabled && this.visible && this.dragging) {
            this.setValueFromMouse(mouseX);
        }
        super.mouseDragged(mc, mouseX, mouseY);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        this.dragging = false;
    }

    private void setValueFromMouse(int mouseX) {
        this.setSliderValue((mouseX - (this.x + 4)) / (double) (this.width - 8));
    }

    private void setSliderValue(double value) {
        double oldValue = this.sliderValue;
        this.sliderValue = this.snapToNearest(value);
        if (oldValue != this.sliderValue && this.onUpdate != null) {
            this.onUpdate.accept(this);
        }
    }

    @Override
    protected String getMessage() {
        this.displayString = this.drawString ? this.prefix + this.getValueString() + this.suffix : "";
        return super.getMessage();
    }

    private double snapToNearest(double value) {
        value = MathHelper.clamp(value, 0D, 1D);
        if (this.stepSize <= 0D || this.minValue == this.maxValue) {
            return value;
        }

        double actualValue = this.minValue + (this.maxValue - this.minValue) * value;
        actualValue = this.stepSize * Math.round(actualValue / this.stepSize);

        if (this.minValue > this.maxValue) {
            actualValue = MathHelper.clamp(actualValue, this.maxValue, this.minValue);
        } else {
            actualValue = MathHelper.clamp(actualValue, this.minValue, this.maxValue);
        }
        return this.toSliderValue(actualValue);
    }

    private double toSliderValue(double value) {
        if (this.minValue == this.maxValue) {
            return 0D;
        }
        return (value - this.minValue) / (this.maxValue - this.minValue);
    }
}
