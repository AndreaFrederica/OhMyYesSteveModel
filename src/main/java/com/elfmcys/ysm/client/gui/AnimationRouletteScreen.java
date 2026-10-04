package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.animation.molang.CustomMolangParser;
import com.elfmcys.ysm.client.event.PlayerMoveEvent;
import com.elfmcys.ysm.client.gui.button.*;
import com.elfmcys.ysm.client.input.AnimationRouletteKey;
import com.elfmcys.ysm.client.input.ExtraAnimationKey;
import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.elfmcys.ysm.molang.parser.ParseException;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import it.unimi.dsi.fastutil.objects.ObjectList;
import com.elfmcys.ysm.proto.mixel.common.StringPair;
import com.elfmcys.ysm.proto.mixel.manifest.info.ConfigForms;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationButton;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationClassify;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class AnimationRouletteScreen extends Screen {
    private static final String SPEC_PREFIX = "#";
    private static final String SPEC_RETURN = "#return";

    /**
     * 配置文本的国际化 key 模板
     */
    private static final String CONFIG_TITLE = "properties.extra_animation_buttons.%s.config_forms.%d.title";
    private static final String CONFIG_DESC = "properties.extra_animation_buttons.%s.config_forms.%d.description";
    private static final String CONFIG_LABELS = "properties.extra_animation_buttons.%s.config_forms.%d.labels.%d";

    private static final int MAX_ROULETTE_COUNT = 8;

    /**
     * 用来缓存当前页面的打开情况，用于在每次打开时，都能记住上一次的页数
     */
    private static final LinkedList<Pair<String, Integer>> CACHE = Lists.newLinkedList();
    /**
     * 缓存当前打开轮盘时的 model id，用于重置轮盘数据
     */
    private static String MODEL_ID = "";

    private int x;
    private int y;
    private int selectId = -1;
    private int hoverConfigId = -1;
    private ExtraAnimationButton configButtons = null;
    private Pair<String, Integer> current;

    /**
     * 当前配置页面滚动的 Y 值
     */
    private int configScrollY = 0;
    /**
     * 当前配置页面最大可以滚动的数值
     */
    private int maxScrollY = 0;
    /**
     * 上下滚动配置的按钮
     */
    @Nullable
    private FlatColorButton scrollConfigUpBtn;
    @Nullable
    private FlatColorButton scrollConfigDownBtn;
    private List<ConfigReadBinding> configReadBindings = List.of();

    private final ObjectList<StringPair> extraAnimations;
    private final Map<String, ExtraAnimationButton> buttonMap;
    private final Map<String, ExtraAnimationClassify> classifyMap;
    private final AnimatableEntity<?> animatableEntity;
    private final ModelRenderTarget model;

    public AnimationRouletteScreen(Map<String, ExtraAnimationButton> buttonMap,
                                   Map<String, ExtraAnimationClassify> classifyMap,
                                   ModelRenderTarget clientModel, AnimatableEntity<?> animatableEntity) {
        super(Component.literal("Animation Roulette GUI"));
        this.model = clientModel;
        this.animatableEntity = animatableEntity;
        this.classifyMap = classifyMap;
        this.buttonMap = buttonMap;

        // 缓存的读取
        this.current = CACHE.peekLast();
        if (this.current != null && this.classifyMap.containsKey(current.getLeft())) {
            this.extraAnimations = this.classifyMap.get(current.getLeft()).extraAnimation();
        } else {
            this.extraAnimations = rootAnimations();
            CACHE.clear();
            CACHE.add(MutablePair.of(StringUtils.EMPTY, this.current == null ? 0 : this.current.getRight()));
            this.current = CACHE.peekLast();
        }
    }

    public AnimationRouletteScreen(String modelId, ModelRenderTarget model, AnimatableEntity<?> animatableEntity) {
        super(Component.literal("Animation Roulette GUI"));
        this.model = model;
        this.animatableEntity = animatableEntity;
        this.classifyMap = model.info().getExtraAnimationClassifications();
        this.buttonMap = model.info().getExtraAnimationButtons();

        // 如果 ID 和缓存的不一致，重置轮盘缓存
        if (!MODEL_ID.equals(modelId)) {
            CACHE.clear();
            MODEL_ID = modelId;
        }
        // 缓存的读取
        if (CACHE.isEmpty()) {
            CACHE.add(MutablePair.of(StringUtils.EMPTY, 0));
        }
        this.current = CACHE.peekLast();
        if (this.classifyMap.containsKey(current.getLeft())) {
            this.extraAnimations = this.classifyMap.get(current.getLeft()).extraAnimation();
        } else {
            this.extraAnimations = rootAnimations();
            CACHE.clear();
            CACHE.add(MutablePair.of(StringUtils.EMPTY, this.current.getRight()));
            this.current = CACHE.peekLast();
        }
    }

    private ObjectList<StringPair> rootAnimations() {
        // Non-player entities use manifest indices in their own network protocol.
        return animatableEntity.getEntity() instanceof Player
                ? com.elfmcys.ysm.client.animation.GeneralAnimationActions.entries(model)
                : model.info().getExtraAnimations();
    }

    @Override
    protected void init() {
        this.clearWidgets();
        var ownerBindings = new ArrayList<ConfigReadBinding>();
        this.configReadBindings = ownerBindings;

        this.x = width / 2 - 70;
        this.y = height / 2 - 8;
        if (this.extraAnimations.size() < (current.getRight() * MAX_ROULETTE_COUNT + 1)) {
            current.setValue(0);
        }
        if (this.extraAnimations.size() <= this.selectId) {
            this.selectId = 0;
        }

        if (this.animatableEntity.getEntity() instanceof Player) {
            // 如果是玩家，那么添加锁定按钮
            this.addRenderableWidget(new FlatColorButton(this.x - 20, this.y - 10, 40, 20, Component.empty(), b -> PlayerMoveEvent.switchLock()) {
                @Override
                @NotNull
                public Component getMessage() {
                    if (PlayerMoveEvent.isLocked()) {
                        return Component.translatable("gui.yes_steve_model.roulette.lock_on");
                    }
                    return Component.translatable("gui.yes_steve_model.roulette.lock_off");
                }
            });
        } else {
            // 否则是停止播放轮盘动画按钮
            this.addRenderableWidget(new FlatColorButton(this.x - 20, this.y - 10, 40, 20, Component.translatable("gui.yes_steve_model.roulette.stop"), b -> {
                Entity entity = this.animatableEntity.getEntity();
                if (entity instanceof Player) ClientProtocolGateway.stopSelfAnimation();
                else ClientProtocolGateway.stopMaidAnimation(entity.getId());
                this.onClose();
            }));
        }

        // 翻页按钮
        this.addRenderableWidget(new FlatColorButton(this.x + 125, this.y - 102, 30, 30, Component.literal("<"), b -> this.pageUp()));
        this.addRenderableWidget(new FlatColorButton(this.x + 240, this.y - 102, 30, 30, Component.literal(">"), b -> this.pageDown()));

        // 添加返回按钮
        Component name = Component.translatable(model.generalMeshResources() != null && animatableEntity.getEntity() instanceof Player
                ? "gui.yes_steve_model.roulette.stop" : "gui.yes_steve_model.model.return");
        this.addRenderableWidget(new FlatColorButton(this.x + 125, this.y - 70, 145, 22, name, b -> this.clickReturn()));

        // 配置按钮
        if (configButtons != null) {
            // 配置上下滚动按钮
            this.scrollConfigUpBtn = new FlatColorButton(this.x + 242, this.y - 46, 28, 60, Component.literal("↑"), b -> {
                this.configScrollUp(50);
                if (this.configScrollY == 0 && this.scrollConfigUpBtn != null) {
                    this.scrollConfigUpBtn.active = false;
                }
                if (this.scrollConfigDownBtn != null) {
                    this.scrollConfigDownBtn.active = true;
                }
            });
            this.scrollConfigDownBtn = new FlatColorButton(this.x + 242, this.y + 50, 28, 60, Component.literal("↓"), b -> {
                this.configScrollDown(50);
                if (this.configScrollY == this.maxScrollY && this.scrollConfigDownBtn != null) {
                    this.scrollConfigDownBtn.active = false;
                }
                if (this.scrollConfigUpBtn != null) {
                    this.scrollConfigUpBtn.active = true;
                }
            });
            this.addRenderableWidget(this.scrollConfigUpBtn);
            this.addRenderableWidget(this.scrollConfigDownBtn);

            final int[] yOffset = {-46};
            final int[] index = {0};
            for (ConfigForms configForm : configButtons.configForms()) {
                this.addConfigForms(configForm, yOffset, index, ownerBindings);
            }
        }
    }

    private void addConfigForms(ConfigForms configForm, int[] yOffset, int[] index,
                                List<ConfigReadBinding> ownerBindings) {
        if (configForm.type().equals("checkbox")) {
            this.executeMolang(configForm.readProgram().source(), result -> {
                minecraft.execute(() -> {
                    if (this.configReadBindings != ownerBindings) {
                        return;
                    }
                    FlatCheckbox checkbox = getFlatCheckbox(configForm, result, yOffset, index);
                    this.addRenderableWidget(checkbox);
                    ownerBindings.add(new ConfigReadBinding(configForm.readProgram().source(),
                            next -> updateCheckbox(checkbox, next)));
                    yOffset[0] += 14;
                    index[0]++;
                    // 最终和 110 的差就是最大滚动高度
                    this.maxScrollY = Math.max(0, yOffset[0] - 110);
                });
            });
        }

        if (configForm.type().equals("range")) {
            this.executeMolang(configForm.readProgram().source(), result -> {
                minecraft.execute(() -> {
                    if (this.configReadBindings != ownerBindings) {
                        return;
                    }
                    FlatSlider slider = getFlatSlider(configForm, result, yOffset, index);
                    this.addRenderableWidget(slider);
                    ownerBindings.add(new ConfigReadBinding(configForm.readProgram().source(),
                            next -> updateSlider(slider, next)));
                    yOffset[0] += 17;
                    index[0]++;
                    // 最终和 110 的差就是最大滚动高度
                    this.maxScrollY = Math.max(0, yOffset[0] - 110);
                });
            });
        }

        if (configForm.type().equals("radio")) {
            this.executeMolang(configForm.readProgram().source(), result -> {
                minecraft.execute(() -> {
                    if (this.configReadBindings != ownerBindings) {
                        return;
                    }
                    addRatioButtons(configForm, result, yOffset, index, ownerBindings);
                });
            });
        }
    }

    private void addRatioButtons(ConfigForms radioForms, String result, int[] yOffset, int[] index,
                                 List<ConfigReadBinding> ownerBindings) {
        var labels = radioForms.labels();
        var selectedIndex = radioIndex(result, labels.size());
        var committedIndex = new int[]{selectedIndex};
        var radioButtons = new ArrayList<FlatCheckbox>(labels.size());

        // 动态改变单选框每行个数
        // 遍历获取最长的行的长度
        int lineMaxWidth = 0;
        int labelsIndex = 0;
        for (var label : labels) {
            String labelStr = LanguageManager.getI18n(this.model,
                    CONFIG_LABELS.formatted(this.configButtons.id(), index[0], labelsIndex), label.name());
            lineMaxWidth = Math.max(lineMaxWidth, font.width(labelStr) + 16);
            labelsIndex++;
        }
        if (lineMaxWidth == 0) {
            lineMaxWidth = 115;
        }
        int countPerLine = Math.max(1, 115 / lineMaxWidth);

        String titleStr = LanguageManager.getI18n(this.model, CONFIG_TITLE.formatted(this.configButtons.id(), index[0]), radioForms.title());
        String descStr = LanguageManager.getI18n(this.model, CONFIG_DESC.formatted(this.configButtons.id(), index[0]), radioForms.description());

        Component title = Component.literal(titleStr);
        Tooltip description = Tooltip.create(Component.literal(descStr));
        int maxHeight = ((labels.size() - 1) / countPerLine + 1) * 14 + 14;
        FlatRatioBox ratioBox = new FlatRatioBox(this.x + 125, this.y + yOffset[0], maxHeight, title);
        ratioBox.setTooltip(description);
        this.addRenderableOnly(ratioBox);

        // 遍历添加每个 label
        int tempYOffset = yOffset[0] + 14;
        for (int i = 0; i < labels.size(); i++) {
            var label = labels.get(i);
            String labelStr = LanguageManager.getI18n(this.model,
                    CONFIG_LABELS.formatted(this.configButtons.id(), index[0], i), label.name());

            Component labelName = Component.literal(labelStr);
            String labelValue = label.actionProgram().source();

            int perWidth = Math.round(110f / countPerLine);
            int xOffset = this.x + 127 + perWidth * (i % countPerLine);

            FlatCheckbox checkbox = new FlatCheckbox(xOffset, this.y + tempYOffset, perWidth, labelName, data -> {
                applyRadioSelection(radioButtons, committedIndex[0]);
                executeMolang(labelValue, ignored -> minecraft.execute(this::refreshConfigValues));
                if (!CustomMolangParser.hasOnlyRoamingAssignment(labelValue) && NetworkHandler.isRemoteChannelPresent() && !ServerConfig.LOW_BANDWIDTH_USAGE.get()) {
                    // 同步到周围的玩家
                    ClientProtocolGateway.submitRouletteExpression(this.animatableEntity.getEntity(), labelValue);
                }
            });
            checkbox.setStateTriggered(selectedIndex == i);

            this.addRenderableWidget(checkbox);
            radioButtons.add(checkbox);

            // 每满 countPerLine 个时，换行
            if (i % countPerLine == (countPerLine - 1)) {
                tempYOffset += 14;
            }
        }

        // 最后记得换行
        yOffset[0] = yOffset[0] + maxHeight + 3;
        index[0] = index[0] + 1;

        // 最终和 110 的差就是最大滚动高度
        this.maxScrollY = Math.max(0, yOffset[0] - 110);

        ownerBindings.add(new ConfigReadBinding(radioForms.readProgram().source(), next -> {
            int nextIndex = radioIndex(next, labels.size());
            if (nextIndex < 0) {
                return;
            }
            committedIndex[0] = nextIndex;
            applyRadioSelection(radioButtons, nextIndex);
        }));
    }

    @NotNull
    private FlatSlider getFlatSlider(ConfigForms rangeForms, String result, int[] yOffset, int[] index) {
        String titleStr = LanguageManager.getI18n(this.model, CONFIG_TITLE.formatted(this.configButtons.id(), index[0]), rangeForms.title());
        String descStr = LanguageManager.getI18n(this.model, CONFIG_DESC.formatted(this.configButtons.id(), index[0]), rangeForms.description());

        Component title = Component.literal(titleStr);
        Tooltip description = Tooltip.create(Component.literal(descStr));
        Float number = transformNumber(result);

        FlatSlider slider = new FlatSlider(this.x + 125, this.y + yOffset[0], title,
                number == null ? 0 : number,
                this.animatableEntity, rangeForms.readProgram().source(), rangeForms.step(),
                rangeForms.min(), rangeForms.max());
        slider.setTooltip(description);

        return slider;
    }

    @NotNull
    private FlatCheckbox getFlatCheckbox(ConfigForms checkboxForms, String result, int[] yOffset, int[] index) {
        String titleStr = LanguageManager.getI18n(this.model, CONFIG_TITLE.formatted(this.configButtons.id(), index[0]), checkboxForms.title());
        String descStr = LanguageManager.getI18n(this.model, CONFIG_DESC.formatted(this.configButtons.id(), index[0]), checkboxForms.description());

        Component title = Component.literal(titleStr);
        Tooltip description = Tooltip.create(Component.literal(descStr));

        Float number = transformNumber(result);

        FlatCheckbox checkbox = new FlatCheckbox(this.x + 125, this.y + yOffset[0], title, data -> {
            // 手动拼接 molang 字符串进行赋值操作
            String value = data ? "1" : "0";
            String molang = checkboxForms.readProgram().source() + "=" + value;
            executeMolang(molang, null);
            if (!CustomMolangParser.hasOnlyRoamingAssignment(molang) && NetworkHandler.isRemoteChannelPresent() && !ServerConfig.LOW_BANDWIDTH_USAGE.get()) {
                // 同步到周围的玩家
                ClientProtocolGateway.submitRouletteExpression(this.animatableEntity.getEntity(), molang);
            }
        }) {
            // 给单选框加上背景
            @Override
            public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
                graphics.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + getHeight(), 0xef_434242);
                super.renderWidget(graphics, mouseX, mouseY, partialTicks);
            }
        };
        checkbox.setStateTriggered(number != null && number > 0);
        checkbox.setTooltip(description);

        return checkbox;
    }

    @Nullable
    static Float transformNumber(String result) {
        if ("null".equals(result)) {
            return 0f;
        } else if (NumberUtils.isParsable(result)) {
            float number = Float.parseFloat(result);
            return Float.isFinite(number) ? number : null;
        } else if (BooleanUtils.toBooleanObject(result) != null) {
            return BooleanUtils.toBoolean(result) ? 1f : 0f;
        }
        return null;
    }

    static int radioIndex(String result, int labelCount) {
        Float number = transformNumber(result);
        if (number == null) {
            return -1;
        }
        int selectedIndex = Math.round(number);
        return 0 <= selectedIndex && selectedIndex < labelCount ? selectedIndex : -1;
    }

    private static void applyRadioSelection(List<FlatCheckbox> radioButtons, int selectedIndex) {
        for (int i = 0; i < radioButtons.size(); i++) {
            radioButtons.get(i).setStateTriggered(i == selectedIndex);
        }
    }

    private static void updateCheckbox(FlatCheckbox checkbox, String result) {
        Float number = transformNumber(result);
        if (number != null) {
            checkbox.setStateTriggered(number > 0);
        }
    }

    private static void updateSlider(FlatSlider slider, String result) {
        Float number = transformNumber(result);
        if (number != null) {
            slider.setDisplayedValue(number);
        }
    }

    private void refreshConfigValues() {
        if (minecraft.screen != this) {
            return;
        }
        var ownerBindings = this.configReadBindings;
        for (var binding : ownerBindings) {
            executeMolang(binding.expression(), result -> minecraft.execute(() -> {
                if (minecraft.screen == this && this.configReadBindings == ownerBindings) {
                    binding.applyResult().accept(result);
                }
            }));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        String patText = StringUtils.joinWith(" > ", CACHE.stream().map(Pair::getLeft).toArray());
        graphics.drawCenteredString(font, Component.translatable("gui.yes_steve_model.roulette.path", patText), this.x + 195, this.y - 100, 0xFFFFFF);

        this.drawRouletteBg(graphics.pose(), mouseX, mouseY);
        this.drawRouletteText(graphics);
        this.drawPageText(graphics);

        // 普通按钮正常渲染
        for (Renderable renderable : this.renderables) {
            if (!(renderable instanceof IConfigFormsButton)) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }

        graphics.enableScissor(0, this.y - 46, this.width, this.y + 110);
        if (mouseY < (this.y - 46) || (this.y + 110) < mouseY) {
            // 鼠标不在滚动区域内，直接把 mouseY 设置为 -1000，防止按钮 hover
            mouseY = -1000;
        } else {
            // 轮盘按钮考虑偏移然后渲染
            mouseY += this.configScrollY;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0, -this.configScrollY, 0);
        for (Renderable renderable : this.renderables) {
            if (renderable instanceof IConfigFormsButton) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.pose().popPose();
        graphics.disableScissor();

        this.drawTooltips(graphics, mouseX, mouseY);
    }

    private void drawTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        if (-1 < selectId && selectId < extraAnimations.size()) {
            String key = extraAnimations.get(selectId).key();
            String descKey = "properties.extra_animation.%s.desc".formatted(key);
            String desc = LanguageManager.getI18n(this.model, descKey, StringUtils.EMPTY);
            if (StringUtils.isNotBlank(desc)) {
                List<FormattedCharSequence> split = font.split(Component.literal(desc), 240);
                graphics.renderTooltip(font, split, mouseX, mouseY);
            }
        }
    }

    private void executeMolang(String molang, @Nullable Consumer<String> resultConsumer) {
        try {
            IValue parsed = CustomMolangParser.parseSingleExpressionUnsafe(molang);
            this.animatableEntity.executeMolangExp(parsed, true, false, resultConsumer);
        } catch (ParseException exception) {
            YesSteveModel.LOGGER.error(exception);
        }
    }

    private void drawPageText(GuiGraphics graphics) {
        graphics.fill(this.x + 157, this.y - 87, this.x + 238, this.y - 72, 0, 0xCF000000);
        String pageText = String.format("%d/%d", current.getRight() + 1, (this.extraAnimations.size() - 1) / MAX_ROULETTE_COUNT + 1);
        graphics.drawCenteredString(font, pageText, this.x + 197, this.y - 83, ChatFormatting.AQUA.getColor());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scroll) {
        if (scroll < 0) {
            // 如果在屏幕的左半边是滚动轮盘，右半边是滚动配置
            if (mouseX < this.x + 110) {
                this.pageDown();
            } else {
                this.configScrollDown(20);
            }
            return true;
        }
        if (scroll > 0) {
            // 如果在屏幕的左半边是滚动轮盘，右半边是滚动配置
            if (mouseX < this.x + 110) {
                this.pageUp();
            } else {
                this.configScrollUp(20);
            }
            return true;
        }
        return false;
    }

    private void pageUp() {
        current.setValue(Math.max(0, current.getRight() - 1));
    }

    private void pageDown() {
        int page = (current.getRight() + 1) * MAX_ROULETTE_COUNT;
        if (this.extraAnimations.size() > page) {
            current.setValue(current.getRight() + 1);
        }
    }

    private void configScrollUp(int count) {
        this.configScrollY = Math.max(0, this.configScrollY - count);
    }

    private void configScrollDown(int count) {
        this.configScrollY = Math.min(this.maxScrollY, this.configScrollY + count);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (-1 < selectId && selectId < extraAnimations.size()) {
            // 点击普通界面
            this.getMinecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            String selectKey = extraAnimations.get(selectId).key();
            if (SPEC_RETURN.equals(selectKey)) {
                // 如果是 #return，那么就是自定义的返回按钮
                this.clickReturn();
            } else if (selectKey.startsWith(SPEC_PREFIX)) {
                // 如果 key 以 # 开头，那么说明选择的是子页面
                this.clickClassify(selectKey);
            } else {
                // 否则执行默认行为
                this.clickDefault(selectKey);
            }
        } else if (-1 < hoverConfigId && hoverConfigId < extraAnimations.size()) {
            // 点击配置按钮
            this.getMinecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            // 以防万一，再检查一次
            String selectValue = extraAnimations.get(hoverConfigId).value_();
            if (selectValue.startsWith(SPEC_PREFIX)) {
                selectValue = selectValue.substring(SPEC_PREFIX.length());
                if (this.buttonMap.containsKey(selectValue)) {
                    clickConfig(selectValue);
                }
            }
        }

        for (GuiEventListener listener : this.children()) {
            double mouseYOffset = mouseY;
            // 配置按钮需要考虑滚动偏移
            if (listener instanceof IConfigFormsButton) {
                mouseYOffset = mouseY + this.configScrollY;
            }
            if (listener.mouseClicked(mouseX, mouseYOffset, button)) {
                this.setFocused(listener);
                if (button == 0) {
                    this.setDragging(true);
                }
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (AnimationRouletteKey.ANIMATION_ROULETTE_KEY.matches(keyCode, scanCode)
            && AnimationRouletteKey.ANIMATION_ROULETTE_KEY.getKeyModifier().isActive(null)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void clickConfig(String buttonName) {
        this.configButtons = this.buttonMap.get(buttonName);
        this.configScrollY = 0;
        this.maxScrollY = 0;
        this.init();
    }

    private void clickDefault(String selectKey) {
        LocalPlayer player = this.getMinecraft().player;
        if (NetworkHandler.isRemoteChannelPresent()) {
            var peekLast = CACHE.peekLast();
            String classifyId = "";
            if (peekLast != null && StringUtils.isNotBlank(peekLast.getLeft())) {
                classifyId = peekLast.getLeft();
            }
            Entity entity = animatableEntity.getEntity();
            if (entity instanceof Player) {
                ClientProtocolGateway.playSelfAnimation(selectKey);
            } else {
                ClientProtocolGateway.playMaidAnimation(entity.getId(), selectId, classifyId);
            }
        } else if (player != null) {
            if (model.generalMeshResources() != null) ClientProtocolGateway.playSelfAnimation(selectKey);
            else player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> cap.playExtraAnimation(selectKey));
        }
        if (player != null && ClientConfig.PRINT_ANIMATION_ROULETTE_MSG.get()) {
            MutableComponent component = Component.translatable("message.yes_steve_model.model.animation_roulette.play", selectKey);
            player.sendSystemMessage(component);
        }
        this.getMinecraft().setScreen(null);
    }

    private void clickClassify(String selectKey) {
        // 最多让你套 5 层
        if (CACHE.size() > 5) {
            LocalPlayer player = this.getMinecraft().player;
            if (player != null) {
                player.sendSystemMessage(Component.translatable("gui.yes_steve_model.roulette.too_long"));
            }
            return;
        }
        String key = selectKey.substring(SPEC_PREFIX.length());
        if (classifyMap.containsKey(key)) {
            CACHE.addLast(MutablePair.of(key, 0));
            AnimationRouletteScreen screen = new AnimationRouletteScreen(this.buttonMap, this.classifyMap, this.model, this.animatableEntity);
            this.getMinecraft().setScreen(screen);
        }
    }

    private void clickReturn() {
        if (model.generalMeshResources() != null && animatableEntity.getEntity() instanceof Player) {
            ClientProtocolGateway.stopSelfAnimation();
            this.getMinecraft().setScreen(null);
            return;
        }
        if (CACHE.size() > 1) {
            CACHE.removeLast();
            AnimationRouletteScreen screen = new AnimationRouletteScreen(this.buttonMap, this.classifyMap, this.model, this.animatableEntity);
            this.getMinecraft().setScreen(screen);
        } else {
            this.getMinecraft().setScreen(null);
        }
    }

    public static void addRootClassify(String keyName) {
        CACHE.clear();
        CACHE.addLast(MutablePair.of("", 0));
        CACHE.addLast(MutablePair.of(keyName, 0));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record ConfigReadBinding(String expression, Consumer<String> applyResult) {
    }

    private void drawRouletteText(GuiGraphics graphics) {
        float startDeg = Mth.PI / MAX_ROULETTE_COUNT;
        int remainSize = extraAnimations.size() - current.getRight() * MAX_ROULETTE_COUNT;
        int radius = 65;

        for (int i = 0; i < Math.min(MAX_ROULETTE_COUNT, remainSize); i++) {
            int index = i + current.getRight() * 8;
            int xPos = (int) (x + radius * Mth.cos(startDeg));
            int yPos = (int) (y + radius * Mth.sin(startDeg) - font.lineHeight / 2f);
            var animation = extraAnimations.get(index);
            String animationValue = animation.value_();

            boolean isClassify = animation.key().startsWith(SPEC_PREFIX);
            boolean hasConfig = animationValue.startsWith(SPEC_PREFIX);

            // 如果含有配置
            if (hasConfig) {
                String configValue = animationValue.substring(SPEC_PREFIX.length());
                if (buttonMap.containsKey(configValue)) {
                    ExtraAnimationButton button = buttonMap.get(configValue);
                    animationValue = button.name();

                    int configR = 35;
                    int configIconX = (int) (x + configR * Mth.cos(startDeg));
                    int configIconY = (int) (y + configR * Mth.sin(startDeg) - font.lineHeight / 2f);
                    graphics.drawCenteredString(font, Component.literal("⚙").withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD), configIconX, configIconY, 0xFFFFFF);
                }
            }

            // 绘制文本
            if (StringUtils.isNoneBlank(animationValue)) {
                String key = LanguageManager.getI18n(this.model,
                        "properties.extra_animation.%s".formatted(animation.key()), animationValue);
                MutableComponent name = Component.literal(key);
                this.drawAnimationName(graphics, name, xPos, yPos, isClassify);
            } else {
                String key = LanguageManager.getI18n(this.model,
                        "properties.extra_animation.%s".formatted(animation.key()), String.valueOf(index));
                MutableComponent name = Component.literal(key);
                graphics.drawCenteredString(font, name, xPos, yPos - 8, 0xF3EFE0);
            }
            // 只有第 0 页显示按键绑定
            // 如果当前是分类菜单，也不显示按键绑定
            if (current.getRight() == 0 && CACHE.size() == 1) {
                this.drawKeyMappingName(graphics, index, xPos, yPos);
            }
            startDeg = startDeg + 2 * Mth.PI / MAX_ROULETTE_COUNT;
        }
    }

    private void drawKeyMappingName(GuiGraphics graphics, int index, int xPos, int yPos) {
        MutableComponent keyText = Component.literal("[ ").withStyle(ChatFormatting.YELLOW);
        KeyMapping keyMapping = ExtraAnimationKey.EXTRA_ANIMATION_KEYS.get(index);
        if (keyMapping.getKey() == InputConstants.UNKNOWN) {
            keyText.append(Component.translatable("key.yes_steve_model.extra_animation.none"));
        } else {
            keyText.append(keyMapping.getTranslatedKeyMessage());
        }
        keyText.append(" ]");
        graphics.drawCenteredString(font, keyText, xPos, yPos + 4, 0xF3EFE0);
    }

    private void drawAnimationName(GuiGraphics graphics, MutableComponent name, int xPos, int yPos, boolean isClassify) {
        int maxLength = 50;
        int lineHeight = font.lineHeight;

        if (isClassify) {
            name = name.withStyle(ChatFormatting.RED);
        }
        var split = font.split(name, maxLength);
        int yoffset = yPos - split.size() * lineHeight + 2;
        // 只有第一页的轮盘能够绑定键位，其他的不行
        // 分类菜单也应该不显示按键
        if (current.getRight() != 0 || CACHE.size() > 1) {
            // 应该下移两行
            yoffset = yoffset + lineHeight;
        }
        for (FormattedCharSequence sequence : split) {
            graphics.drawCenteredString(font, sequence, xPos, yoffset, 0xF3EFE0);
            yoffset = yoffset + lineHeight;
        }
    }

    private void drawRouletteBg(PoseStack pPoseStack, int mouseX, int mouseY) {
        // extraAnimations 可能为空
        if (this.extraAnimations.isEmpty()) {
            return;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder bufferbuilder = tesselator.getBuilder();
        bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f pMatrix = pPoseStack.last().pose();

        int count = 8;
        float theta = (float) Mth.atan2(mouseY - y, mouseX - x);
        if (theta < 0) {
            theta = Mth.PI * 2 + theta;
        }
        float distance = Mth.sqrt(Mth.square(mouseY - y) + Mth.square(mouseX - x));
        boolean isSelected = false;
        boolean isConfigSelected = false;

        for (int i = 0; i < Math.min(8, extraAnimations.size() - current.getRight() * 8); i++) {
            float spacingDeg = Mth.PI / 90;
            float startDeg = (2 * Mth.PI / count) * i + spacingDeg;
            float endDeg = (2 * Mth.PI / count) * (i + 1) - spacingDeg;
            int index = i + current.getRight() * 8;
            boolean hasConfig = extraAnimations.get(index).value_().startsWith(SPEC_PREFIX);

            isSelected = onDrawFan(startDeg, theta, endDeg, distance, isSelected, hasConfig, i, bufferbuilder, pMatrix);

            boolean isConfigHover = startDeg < theta && theta < endDeg && 20 < distance && distance < 50;
            if (hasConfig) {
                if (isConfigHover) {
                    drawFan(bufferbuilder, pMatrix, 15, 50, startDeg, endDeg, 0xf000ceff);
                    isConfigSelected = true;
                    this.hoverConfigId = index;
                } else {
                    drawFan(bufferbuilder, pMatrix, 25, 50, startDeg, endDeg, 0x7000ceff);
                }
            }
        }
        if (!isSelected) {
            this.selectId = -1;
        }
        if (!isConfigSelected) {
            this.hoverConfigId = -1;
        }

        tesselator.end();
        RenderSystem.disableBlend();
    }

    private boolean onDrawFan(float startDeg, float theta, float endDeg, float distance, boolean isSelected, boolean hasConfig, int i, BufferBuilder bufferbuilder, Matrix4f pMatrix) {
        boolean hovered = startDeg < theta && theta < endDeg && 50 < distance && distance < 100;
        if (hovered) {
            isSelected = true;
            this.selectId = i + current.getRight() * 8;
        }
        if (hovered && i < extraAnimations.size()) {
            if (hasConfig) {
                drawFan(bufferbuilder, pMatrix, 50, 115, startDeg, endDeg, 0xf0FFB100);
                drawFan(bufferbuilder, pMatrix, 25, 50, startDeg, endDeg, 0x90000000);
            } else {
                drawFan(bufferbuilder, pMatrix, 25, 115, startDeg, endDeg, 0xf0FFB100);
            }
        } else {
            drawFan(bufferbuilder, pMatrix, 25, 105, startDeg, endDeg, 0x90000000);
        }

        return isSelected;
    }

    private void drawFan(BufferBuilder builder, Matrix4f matrix4f, float rIn, float rOut, float startDeg, float endDeg, int color) {
        float alpha = (color >> 24 & 255) / 255.0F;
        float red = (color >> 16 & 255) / 255.0F;
        float green = (color >> 8 & 255) / 255.0F;
        float blue = (color & 255) / 255.0F;
        builder.vertex(matrix4f, x + rOut * Mth.cos(startDeg), y + rOut * Mth.sin(startDeg), 0).color(red, green, blue, alpha).endVertex();
        builder.vertex(matrix4f, x + rIn * Mth.cos(startDeg), y + rIn * Mth.sin(startDeg), 0).color(red, green, blue, alpha).endVertex();
        builder.vertex(matrix4f, x + rIn * Mth.cos(endDeg), y + rIn * Mth.sin(endDeg), 0).color(red, green, blue, alpha).endVertex();
        builder.vertex(matrix4f, x + rOut * Mth.cos(endDeg), y + rOut * Mth.sin(endDeg), 0).color(red, green, blue, alpha).endVertex();
    }
}
