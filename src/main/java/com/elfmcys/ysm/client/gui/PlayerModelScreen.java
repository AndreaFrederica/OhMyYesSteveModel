package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.AuthModelsCapabilityProvider;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.StarModelsCapabilityProvider;
import com.elfmcys.ysm.client.ClientProxy;
import com.elfmcys.ysm.client.gui.button.CatalogModelButton;
import com.elfmcys.ysm.client.gui.button.Checkbox;
import com.elfmcys.ysm.client.gui.button.FailedCatalogModelButton;
import com.elfmcys.ysm.client.gui.button.FlatColorButton;
import com.elfmcys.ysm.client.gui.button.FlatIconButton;
import com.elfmcys.ysm.client.gui.button.PackButton;
import com.elfmcys.ysm.client.gui.button.StarButton;
import com.elfmcys.ysm.client.input.PlayerModelScreenKey;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.config.Config;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.client.ModelPackInfo;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetBatch;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import com.elfmcys.ysm.util.GuiRenderUtil;
import com.elfmcys.ysm.util.InputCheckUtil;
import com.elfmcys.ysm.util.ModelIdUtil;
import com.elfmcys.ysm.util.TextMeasureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PlayerModelScreen extends Screen {
    private static final CustomGuiPlayerEntity[] MODEL_PREVIEW_ENTITY = new CustomGuiPlayerEntity[10];

    private final ClientModelService service = ClientModelService.instance();
    private final CatalogBrowserState browser = new CatalogBrowserState();
    private final HashSet<String> clientNotDisplayModels = new HashSet<>();
    private final List<CatalogModelButton> modelButtons = new ArrayList<>();
    private final List<FailedCatalogModelButton> failedButtons = new ArrayList<>();
    private final List<PackButton> packButtons = new ArrayList<>();
    @Nullable
    private CatalogModelButton focusedModelButton;
    private final Map<String, ModelPackDescriptor> packDescriptors = new LinkedHashMap<>();
    private final CatalogDemandTracker demand = new CatalogDemandTracker(Minecraft.getSystemTime());
    private ClientAssetBatch pageAssets;
    private List<String> renderedPage = List.of();
    private boolean pageSubmitted;
    private GuiTextField textField;
    protected int x;
    protected int y;

    static {
        for (var index = 0; index < MODEL_PREVIEW_ENTITY.length; index++) {
            MODEL_PREVIEW_ENTITY[index] = new CustomGuiPlayerEntity();
        }
    }

    public PlayerModelScreen() {
        super();
        ClientSessionRuntime.reopenCatalog();
        if (NetworkHandler.isRemoteChannelPresent()) {
            clientNotDisplayModels.addAll(List.of(ServerConfig.CLIENT_NOT_DISPLAY_MODEL_PATHS));
        }
        rebuildCatalog(service.catalog());
    }

    protected PlayerTextureScreen getTextureScreen(PlayerModelScreen parent, Hash256 modelHash,
                                                    ModelRenderTarget renderTarget) {
        return new PlayerTextureScreen(parent, modelHash, renderTarget);
    }

    protected ModelInfoScreen getModelInfoScreen(PlayerModelScreen parent, ModelRenderTarget model) {
        return new ModelInfoScreen(parent, model);
    }

    protected void selectModel(Hash256 hash, String path, String texture, ModelRenderTarget renderTarget) {
        var player = Minecraft.getMinecraft().player;
        if (player == null) {
            return;
        }
        var capability = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
        if (capability != null) {
            var sessionState = ClientSessionRuntime.state()
                    .orElse(ClientModelSession.State.LOCAL);
            if (sessionState == ClientModelSession.State.ACTIVE) {
                if (capability.hasRoamingStorage(hash.roamingHash())) {
                    capability.updateModelAndTexture(hash, texture);
                }
                ClientProtocolGateway.selectModel(hash, texture);
            } else if (sessionState
                    == ClientModelSession.State.LOCAL) {
                capability.updateModelAndTexture(hash, texture);
            }
        }
    }

    @Override
    public void initGui() {
        closePage();
        if (browser.catalog() != service.catalog()) {
            rebuildCatalog(service.catalog());
        }
        calculateModelList();
        pageAssets = service.createAssetBatch();

        x = (width - 420) / 2;
        y = (height - 235) / 2;
        var previousSearch = textField == null ? "" : textField.getText();
        var focused = textField != null && textField.isFocused();
        textField = new GuiTextField(0, fontRenderer, x + 144, y + 6, 140, 16);
        textField.setText(previousSearch);
        textField.setTextColor(0xF3EFE0);
        textField.setFocused(focused);
        textField.setCursorPositionEnd();

        addHeaderButtons();
        addPageButtons();
        addCatalogButtons(pageAssets);
        renderedPage = pageSignature();
        maybeSubmitPage(Minecraft.getSystemTime());
    }

    private void addHeaderButtons() {
        addButton(new FlatIconButton(x + 5, y + 5, 20, 20, 80, 16, ignored -> {
            var player = Minecraft.getMinecraft().player;
            if (player != null) {
                var capability = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
                if (capability != null) {
                    var model = capability.getModelRenderTarget();
                    if (model != null && model.info().hasMetadata()) {
                        Minecraft.getMinecraft().displayGuiScreen(getModelInfoScreen(this, model));
                    }
                }
            }
        })).setTooltips("gui.yes_steve_model.model.info");
        addButton(new FlatIconButton(x + 28, y + 5, 79, 20, 32, 16, ignored -> {
            var player = Minecraft.getMinecraft().player;
            if (player != null) {
                var capability = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
                if (capability != null) {
                    var model = capability.getModelRenderTarget();
                    if (model != null && capability.getModelHash() != null) {
                        Minecraft.getMinecraft().displayGuiScreen(getTextureScreen(this, capability.getModelHash(), model));
                    }
                }
            }
        })).setTooltips("gui.yes_steve_model.model.texture");
        addButton(new StarButton(x + 110, y + 5));

        if (StringUtils.isNotBlank(browser.currentPack())) {
            addButton(new FlatIconButton(x + 110, y + 27, 20, 20, 0, 32,
                    ignored -> backToParent()).setTooltips("gui.back"));
        }

        addButton(new Checkbox(x + 5, y - 22,
                I18n.format("gui.yes_steve_model.show_model_id_first"),
                fontRenderer, ClientConfig.SHOW_MODEL_ID_FIRST, b -> {
            ClientConfig.SHOW_MODEL_ID_FIRST = ((Checkbox) b).selected();
            Config.save();
        }));

        addCategoryButton(x + 328, 32, CatalogBrowserState.Category.ALL, "gui.yes_steve_model.all_models");
        addCategoryButton(x + 308, 48, CatalogBrowserState.Category.AUTH, "gui.yes_steve_model.auth_models");
        addCategoryButton(x + 288, 0, CatalogBrowserState.Category.STAR, "gui.yes_steve_model.star_models");
        addButton(new FlatIconButton(x + 397, y + 5, 18, 18, 16, 16,
                ignored -> mc.displayGuiScreen(new ConfigScreen(this)))
                .setTooltips("gui.yes_steve_model.config"));
        addButton(new FlatIconButton(x + 377, y + 5, 18, 18, 0, 16,
                ignored -> ClientProxy.openDownloadScreen(this))
                .setTooltips("gui.yes_steve_model.download"));
        addButton(new FlatIconButton(x + 357, y + 5, 18, 18, 80, 0,
                ignored -> mc.displayGuiScreen(new OpenModelFolderScreen(this)))
                .setTooltips("gui.yes_steve_model.open_model_folder.open"));
    }

    private void addCategoryButton(int buttonX, int u, CatalogBrowserState.Category target, String tooltip) {
        addButton(new FlatIconButton(buttonX, y + 5, 18, 18, u, 0, ignored -> {
            if (browser.category() != target) {
                demand.switchPage(Minecraft.getSystemTime());
                browser.category(target);
                refreshGui();
            }
        }).setTooltips(tooltip));
    }

    private void addPageButtons() {
        addButton(new FlatColorButton(x + 198, y + 215, 52, 14,
                I18n.format("gui.yes_steve_model.pre_page"), ignored -> {
            if (browser.page() > 0) {
                demand.switchPage(Minecraft.getSystemTime());
                browser.page(browser.page() - 1);
                refreshGui();
            }
        }));
        addButton(new FlatColorButton(x + 308, y + 215, 52, 14,
                I18n.format("gui.yes_steve_model.next_page"), ignored -> {
            if (browser.page() < browser.maxPage()) {
                demand.switchPage(Minecraft.getSystemTime());
                browser.page(browser.page() + 1);
                refreshGui();
            }
        }));
    }

    private void addCatalogButtons(ClientAssetBatch assets) {
        var player = mc == null ? null : mc.player;
        if (player == null) {
            return;
        }
        var auth = player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP, null);
        var sessionGrants = ClientSessionRuntime.authoritativeGrants();
        for (var slot = 0; slot < 10; slot++) {
            var index = slot + browser.page() * 10;
            var xStart = x + 143 + 55 * (slot % 5);
            var yStart = y + 28 + 93 * (slot / 5);
            if (index < browser.packs().size()) {
                var pack = browser.packs().get(index);
                var button = new PackButton(xStart, yStart, 52, 90, pack,
                        packDescriptors.get(pack.hierarchy()), assets, ignored -> {
                    demand.switchPage(Minecraft.getSystemTime());
                    browser.enterPack(pack.hierarchy());
                    refreshGui();
                });
                packButtons.add(button);
                addButton(button);
                continue;
            }
            index -= browser.packs().size();
            if (index >= 0 && index < browser.models().size()) {
                var entry = browser.models().get(index);
                var needAuth = entry.authorizationRequired()
                        && sessionGrants.map(grants -> !grants.contains(entry.modelHash()))
                        .orElseGet(() -> auth == null || !auth.containModel(entry.modelHash()));
                var button = new CatalogModelButton(xStart, yStart, entry, needAuth,
                        assets, MODEL_PREVIEW_ENTITY[slot], this::selectModel,
                        (hash, path, renderTarget) -> Minecraft.getMinecraft().displayGuiScreen(
                                getTextureScreen(this, hash, renderTarget)));
                modelButtons.add(button);
                addButton(button);
                continue;
            }
            index -= browser.models().size();
            if (index >= 0 && index < browser.failed().size()) {
                var button = new FailedCatalogModelButton(
                        xStart, yStart, browser.failed().get(index));
                failedButtons.add(button);
                addButton(button);
            }
        }
    }

    private void calculateModelList() {
        var player = mc == null ? null : mc.player;
        var auth = player == null ? null
                : player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP, null);
        var sessionGrants = ClientSessionRuntime.authoritativeGrants();
        var stars = player == null ? null
                : player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP, null);
        browser.filter(textField == null ? "" : textField.getText(), locale(), clientNotDisplayModels,
                hash -> sessionGrants.map(grants -> grants.contains(hash))
                        .orElseGet(() -> auth != null && auth.containModel(hash)),
                hash -> stars != null && stars.containModel(hash));
    }

    private void rebuildCatalog(ClientCatalogSnapshot next) {
        packDescriptors.clear();
        next.packs().forEach(pack -> packDescriptors.putIfAbsent(pack.hierarchy(), pack));
        browser.rebuild(next, this::packInfo);
    }

    private ModelPackInfo packInfo(ModelPackDescriptor descriptor) {
        var languages = new LinkedHashMap<String, Map<String, String>>();
        descriptor.translations().forEach((locale, text) -> languages.put(locale,
                Map.of("name", text.name(), "description", text.description())));
        return new ModelPackInfo(descriptor.hierarchy(), descriptor.name(), descriptor.description(),
                null, Map.copyOf(languages));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        var nowMillis = Minecraft.getSystemTime();
        var hovered = modelButtons.stream()
                .filter(button -> button.isMouseOver(mouseX, mouseY))
                .map(CatalogModelButton::modelHash)
                .findFirst().orElse(null);
        demand.observeHover(hovered, nowMillis);
        var hoverGeneration = demand.hoverGeneration();
        modelButtons.forEach(button -> button.updateDemand(hoverGeneration,
                demand.mayBake(button.modelHash(), nowMillis)));
        drawDefaultBackground();
        this.drawGradientRect(x, y, x + 135, y + 235, 0xFF222222, 0xFF222222);
        this.drawGradientRect(x + 138, y, x + 420, y + 235, 0xFF222222, 0xFF222222);
        this.drawGradientRect(x + 351, y + 7, x + 352, y + 21, 0xFFF3EFE0, 0xFFF3EFE0);
        textField.drawTextBox();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        renderReferenceEntity(mouseX, mouseY, partialTick);

        if (textField.getText().isEmpty() && !textField.isFocused()) {
            this.drawString(fontRenderer, TextFormatting.ITALIC + I18n.format("gui.yes_steve_model.search"),
                    x + 148, y + 10, 0x777777);
        }
        var page = "%d/%d".formatted(browser.page() + 1, browser.maxPage() + 1);
        this.drawString(fontRenderer, page, x + 138 + (282 - fontRenderer.getStringWidth(page)) / 2,
                y + 223 - fontRenderer.FONT_HEIGHT / 2, 0xF3EFE0);
        var version = Loader.instance().getIndexedModList().get(YesSteveModel.MOD_ID).getVersion();
        this.drawString(fontRenderer, TextFormatting.DARK_GRAY + version, x + 2, y + 226, 0xffffffff);
        if (!browser.currentPack().isBlank()) {
            this.drawString(fontRenderer,TextFormatting.GRAY + "\uD83D\uDCC2 " + browser.currentPack(),
                    x + 142, y - 12, 0xF3EFE0);
        }
        if (service.loadingCount() > 0) {
            var loading = Integer.toString(service.loadingCount());
            this.drawString(fontRenderer, TextFormatting.DARK_GRAY + loading, x + 414 - fontRenderer.getStringWidth(loading), y + 218, 0xffffffff);
        }

        super.drawScreen(mouseX, mouseY, partialTick);
        buttonList.stream().filter(FlatIconButton.class::isInstance).map(FlatIconButton.class::cast)
                .forEach(button -> button.renderToolTip(this, mouseX, mouseY));
        modelButtons.forEach(button -> button.renderTooltip(this, mouseX, mouseY));
        failedButtons.forEach(button -> button.renderTooltip(this, mouseX, mouseY));
        buttonList.stream().filter(PackButton.class::isInstance).map(PackButton.class::cast)
                .forEach(button -> button.renderComponentTooltip(this, mouseX, mouseY));
    }

    protected void renderReferenceEntity(int mouseX, int mouseY, float partialTick) {
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player == null) {
            return;
        }
        GuiRenderUtil.enableScissor(x + 5, y + 29, 125, 171);
        GuiInventory.drawEntityOnScreen(x + 67, y + 190, 70,
                x + 67 - mouseX, y + 85 - mouseY, player);
        GuiRenderUtil.disableScissor();

        var capability = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
        if (capability != null) {
            var renderTarget = capability.getModelRenderTarget();
            var fallback = capability.getModelHash() == null ? "default" : service.displayPath(capability.getModelHash());
            var name = renderTarget == null ? ModelIdUtil.getFileNameFromPath(fallback)
                    : renderTarget.getDisplayName(ModelIdUtil.getFileNameFromPath(fallback));
            var lines = TextMeasureUtil.split(fontRenderer, name, 125);
            var lineY = y + 205;
            for (String line : lines) {
                this.drawString(fontRenderer, line, x + (135 - fontRenderer.getStringWidth(line)) / 2, lineY, 0xF3EFE0);
                lineY += 10;
            }
        }
    }

    @Override
    public void updateScreen() {
        textField.updateCursorCounter();
        if (browser.catalog() != service.catalog()) {
            var previousPage = renderedPage;
            rebuildCatalog(service.catalog());
            calculateModelList();
            if (!previousPage.equals(pageSignature())) {
                demand.switchPage(Minecraft.getSystemTime());
                refreshGui();
            }
        }
        maybeSubmitPage(Minecraft.getSystemTime());
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        textField.mouseClicked(mouseX, mouseY, button);
        if (isMouseWithin(mouseX, mouseY, textField.x, textField.y, textField.width, textField.height) && button == LEFT_MOUSE_BUTTON) {
            return;
        }

        // FIXME：清理事件分发，别特判了
        // 保持行为和 1.12.2 一致，不提前返回
        boolean handled = false;
        for (var guiButton : this.buttonList) {
            if (guiButton instanceof CatalogModelButton modelButton &&
                    modelButton.mouseClicked(this.mc, mouseX, mouseY, button)) {
                if (button == 0) {
                    if (this.focusedModelButton != null && this.focusedModelButton != modelButton) {
                        this.focusedModelButton.setFocused(false);
                    }
                    this.focusedModelButton = modelButton;
                    modelButton.setFocused(true);
                }
                var event = new GuiScreenEvent.ActionPerformedEvent.Pre(this, guiButton, this.buttonList);
                if (MinecraftForge.EVENT_BUS.post(event)) break;
                guiButton = event.getButton();
                this.selectedButton = guiButton;
                guiButton.playPressSound(this.mc.getSoundHandler());
                this.actionPerformed(guiButton);
                if (this.equals(this.mc.currentScreen)) MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.ActionPerformedEvent.Post(this, guiButton, this.buttonList));
                handled = true;
            }
        }

        var preSelected = this.selectedButton;
        this.selectedButton = null;
        super.mouseClicked(mouseX, mouseY, button);
        if (this.selectedButton != null) handled = true;
        this.selectedButton = preSelected;

        if (!handled && button == RIGHT_MOUSE_BUTTON && !browser.currentPack().isBlank()) {
            mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1));
            backToParent();
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws IOException {
        if (InputCheckUtil.matches(PlayerModelScreenKey.PLAYER_MODEL_KEY, keyCode) && !textField.isFocused()) {
            mc.displayGuiScreen(null);
            return;
        }
        var previous = textField.getText();
        if (textField.textboxKeyTyped(typedChar, keyCode)) {
            if (!Objects.equals(previous, textField.getText())) {
                demand.switchPage(Minecraft.getSystemTime());
                browser.resetPage();
                refreshGui();
            }
            return;
        }
        if (!(textField.isFocused() && textField.getVisible() && keyCode != Keyboard.KEY_ESCAPE)) {
            super.keyTyped(typedChar, keyCode);
        }
    }

    @Override
    public void mouseScrolled(int mouseX, int mouseY, int delta) {
        if (delta != 0 && mouseX > x + 143 && mouseX < x + 430 && mouseY > y + 25 && mouseY < y + 235) {
            if (delta > 0 && browser.page() > 0) {
                demand.switchPage(Minecraft.getSystemTime());
                browser.page(browser.page() - 1);
                refreshGui();
            } else if (delta < 0 && browser.page() < browser.maxPage()) {
                demand.switchPage(Minecraft.getSystemTime());
                browser.page(browser.page() + 1);
                refreshGui();
            }
            return;
        }
        super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void onResize(@NotNull Minecraft minecraft, int width, int height) {
        var search = textField == null ? "" : textField.getText();
        super.onResize(minecraft, width, height);
        textField.setText(search);
    }

    @Override
    public void onGuiClosed() {
        demand.close();
        closePage();
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void closeModelButtons() {
        focusedModelButton = null;
        modelButtons.forEach(CatalogModelButton::close);
        modelButtons.clear();
        failedButtons.clear();
    }

    private void closePage() {
        closeModelButtons();
        packButtons.forEach(PackButton::close);
        packButtons.clear();
        if (pageAssets != null) {
            pageAssets.close();
            pageAssets = null;
        }
        pageSubmitted = false;
    }

    private void backToParent() {
        demand.switchPage(Minecraft.getSystemTime());
        browser.backToParent();
        refreshGui();
    }

    private void maybeSubmitPage(long nowMillis) {
        if (pageAssets == null || pageSubmitted) {
            return;
        }
        var hasRemoteMiss = pageAssets.hasRemoteRequests();
        if (!demand.maySubmitPage(hasRemoteMiss, nowMillis)) {
            return;
        }
        pageSubmitted = true;
        pageAssets.submit();
    }

    private List<String> pageSignature() {
        var result = new ArrayList<String>(10);
        for (var slot = 0; slot < 10; slot++) {
            var index = slot + browser.page() * 10;
            if (index < browser.packs().size()) {
                var pack = browser.packs().get(index);
                var descriptor = packDescriptors.get(pack.hierarchy());
                result.add("pack:" + pack.hierarchy() + ":" + pack.name() + ":"
                        + pack.desc() + ":" + pack.lang() + ":"
                        + (descriptor == null ? "synthetic" : descriptor.rootKind() + ":"
                        + descriptor.coverSize() + ":"
                        + Objects.hashCode(descriptor.coverHash())));
                continue;
            }
            index -= browser.packs().size();
            if (index >= 0 && index < browser.models().size()) {
                var entry = browser.models().get(index);
                result.add("model:" + entry.modelHash() + ":"
                        + entry.content().representation().identity() + ":"
                        + entry.entry() + ":" + entry.origin());
                continue;
            }
            index -= browser.models().size();
            if (index >= 0 && index < browser.failed().size()) {
                result.add("failed:" + browser.failed().get(index));
            } else {
                result.add("empty");
            }
        }
        return List.copyOf(result);
    }

    private String locale() {
        return Minecraft.getMinecraft().getLanguageManager().getCurrentLanguage().getLanguageCode();
    }

}
