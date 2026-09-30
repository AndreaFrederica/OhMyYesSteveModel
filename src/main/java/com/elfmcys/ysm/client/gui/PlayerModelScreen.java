package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.AuthModelsCapabilityProvider;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.StarModelsCapabilityProvider;
import com.elfmcys.ysm.client.event.DownloadScreenInterModEvent;
import com.elfmcys.ysm.client.gui.button.CatalogModelButton;
import com.elfmcys.ysm.client.gui.button.CallbackCheckbox;
import com.elfmcys.ysm.client.gui.button.FailedCatalogModelButton;
import com.elfmcys.ysm.client.gui.button.FlatColorButton;
import com.elfmcys.ysm.client.gui.button.FlatIconButton;
import com.elfmcys.ysm.client.gui.button.PackButton;
import com.elfmcys.ysm.client.gui.button.StarButton;
import com.elfmcys.ysm.client.input.PlayerModelScreenKey;
import com.elfmcys.ysm.config.ClientConfig;
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
import com.elfmcys.ysm.util.ModelIdUtil;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.fml.ModList;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.glfw.GLFW;

public class PlayerModelScreen extends Screen {
    private static final CustomGuiPlayerEntity[] MODEL_PREVIEW_ENTITY = new CustomGuiPlayerEntity[10];

    private final ClientModelService service = ClientModelService.instance();
    private final CatalogBrowserState browser = new CatalogBrowserState();
    private final HashSet<String> clientNotDisplayModels = new HashSet<>();
    private final List<CatalogModelButton> modelButtons = new ArrayList<>();
    private final List<FailedCatalogModelButton> failedButtons = new ArrayList<>();
    private final List<PackButton> packButtons = new ArrayList<>();
    private final Map<String, ModelPackDescriptor> packDescriptors = new LinkedHashMap<>();
    private final CatalogDemandTracker demand = new CatalogDemandTracker(Util.getMillis());
    private ClientAssetBatch pageAssets;
    private List<String> renderedPage = List.of();
    private boolean pageSubmitted;
    private EditBox textField;
    protected int x;
    protected int y;

    static {
        for (var index = 0; index < MODEL_PREVIEW_ENTITY.length; index++) {
            MODEL_PREVIEW_ENTITY[index] = new CustomGuiPlayerEntity();
        }
    }

    public PlayerModelScreen() {
        super(Component.literal("YSM Player Model GUI"));
        ClientSessionRuntime.reopenCatalog();
        if (NetworkHandler.isRemoteChannelPresent()) {
            clientNotDisplayModels.addAll(ServerConfig.CLIENT_NOT_DISPLAY_MODEL_PATHS.get());
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
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(capability -> {
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
        });
    }

    @Override
    protected void init() {
        closePage();
        clearWidgets();
        if (browser.catalog() != service.catalog()) {
            rebuildCatalog(service.catalog());
        }
        calculateModelList();
        pageAssets = service.createAssetBatch();

        x = (width - 420) / 2;
        y = (height - 235) / 2;
        var previousSearch = textField == null ? "" : textField.getValue();
        var focused = textField != null && textField.isFocused();
        textField = new EditBox(font, x + 144, y + 6, 140, 16, Component.literal("YSM Search Box"));
        textField.setValue(previousSearch);
        textField.setTextColor(0xF3EFE0);
        textField.setFocused(focused);
        textField.moveCursorToEnd();
        addWidget(textField);

        addHeaderButtons();
        addPageButtons();
        addCatalogButtons(pageAssets);
        renderedPage = pageSignature();
        maybeSubmitPage(Util.getMillis());
    }

    private void addHeaderButtons() {
        addRenderableWidget(new FlatIconButton(x + 5, y + 5, 20, 20, 80, 16, ignored -> {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(capability -> {
                    var model = capability.getModelRenderTarget();
                    if (model != null && model.info().hasMetadata()) {
                        Minecraft.getInstance().setScreen(getModelInfoScreen(this, model));
                    }
                });
            }
        })).setTooltips("gui.yes_steve_model.model.info");
        addRenderableWidget(new FlatIconButton(x + 28, y + 5, 79, 20, 32, 16, ignored -> {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(capability -> {
                    var model = capability.getModelRenderTarget();
                    if (model != null && capability.getModelHash() != null) {
                        Minecraft.getInstance().setScreen(getTextureScreen(this, capability.getModelHash(), model));
                    }
                });
            }
        })).setTooltips("gui.yes_steve_model.model.texture");
        addRenderableWidget(new StarButton(x + 110, y + 5));

        if (StringUtils.isNotBlank(browser.currentPack())) {
            addRenderableWidget(new FlatIconButton(x + 110, y + 27, 20, 20, 0, 32,
                    ignored -> backToParent()).setTooltips("gui.back"));
        }

        addRenderableWidget(new CallbackCheckbox(x + 5, y - 22, 20, 20,
                Component.translatable("gui.yes_steve_model.show_model_id_first"),
                ClientConfig.SHOW_MODEL_ID_FIRST.get(), true, selected -> {
                ClientConfig.SHOW_MODEL_ID_FIRST.set(selected);
                ClientConfig.SHOW_MODEL_ID_FIRST.save();
        }));

        addCategoryButton(x + 328, 32, CatalogBrowserState.Category.ALL, "gui.yes_steve_model.all_models");
        addCategoryButton(x + 308, 48, CatalogBrowserState.Category.AUTH, "gui.yes_steve_model.auth_models");
        addCategoryButton(x + 288, 0, CatalogBrowserState.Category.STAR, "gui.yes_steve_model.star_models");
        addRenderableWidget(new FlatIconButton(x + 397, y + 5, 18, 18, 16, 16,
                ignored -> getMinecraft().setScreen(new ConfigScreen(this)))
                .setTooltips("gui.yes_steve_model.config"));
        addRenderableWidget(new FlatIconButton(x + 377, y + 5, 18, 18, 0, 16,
                ignored -> DownloadScreenInterModEvent.openDownloadScreen(this))
                .setTooltips("gui.yes_steve_model.download"));
        addRenderableWidget(new FlatIconButton(x + 357, y + 5, 18, 18, 80, 0,
                ignored -> getMinecraft().setScreen(new OpenModelFolderScreen(this)))
                .setTooltips("gui.yes_steve_model.open_model_folder.open"));
    }

    private void addCategoryButton(int buttonX, int u, CatalogBrowserState.Category target, String tooltip) {
        addRenderableWidget(new FlatIconButton(buttonX, y + 5, 18, 18, u, 0, ignored -> {
            if (browser.category() != target) {
                demand.switchPage(Util.getMillis());
                browser.category(target);
                init();
            }
        }).setTooltips(tooltip));
    }

    private void addPageButtons() {
        addRenderableWidget(new FlatColorButton(x + 198, y + 215, 52, 14,
                Component.translatable("gui.yes_steve_model.pre_page"), ignored -> {
            if (browser.page() > 0) {
                demand.switchPage(Util.getMillis());
                browser.page(browser.page() - 1);
                init();
            }
        }));
        addRenderableWidget(new FlatColorButton(x + 308, y + 215, 52, 14,
                Component.translatable("gui.yes_steve_model.next_page"), ignored -> {
            if (browser.page() < browser.maxPage()) {
                demand.switchPage(Util.getMillis());
                browser.page(browser.page() + 1);
                init();
            }
        }));
    }

    private void addCatalogButtons(ClientAssetBatch assets) {
        var player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            return;
        }
        var auth = player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP).resolve().orElse(null);
        var sessionGrants = ClientSessionRuntime.authoritativeGrants();
        for (var slot = 0; slot < 10; slot++) {
            var index = slot + browser.page() * 10;
            var xStart = x + 143 + 55 * (slot % 5);
            var yStart = y + 28 + 93 * (slot / 5);
            if (index < browser.packs().size()) {
                var pack = browser.packs().get(index);
                var button = new PackButton(xStart, yStart, 52, 90, pack,
                        packDescriptors.get(pack.hierarchy()), assets, ignored -> {
                    demand.switchPage(Util.getMillis());
                    browser.enterPack(pack.hierarchy());
                    init();
                });
                packButtons.add(button);
                addRenderableWidget(button);
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
                        (hash, path, renderTarget) -> Minecraft.getInstance().setScreen(
                                getTextureScreen(this, hash, renderTarget)));
                modelButtons.add(button);
                addRenderableWidget(button);
                continue;
            }
            index -= browser.models().size();
            if (index >= 0 && index < browser.failed().size()) {
                var button = new FailedCatalogModelButton(
                        xStart, yStart, browser.failed().get(index));
                failedButtons.add(button);
                addRenderableWidget(button);
            }
        }
    }

    private void calculateModelList() {
        var player = minecraft == null ? null : minecraft.player;
        var auth = player == null ? null
                : player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP).resolve().orElse(null);
        var sessionGrants = ClientSessionRuntime.authoritativeGrants();
        var stars = player == null ? null
                : player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP).resolve().orElse(null);
        browser.filter(textField == null ? "" : textField.getValue(), locale(), clientNotDisplayModels,
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var nowMillis = Util.getMillis();
        var hovered = modelButtons.stream()
                .filter(button -> button.isMouseOver(mouseX, mouseY))
                .map(CatalogModelButton::modelHash)
                .findFirst().orElse(null);
        demand.observeHover(hovered, nowMillis);
        var hoverGeneration = demand.hoverGeneration();
        modelButtons.forEach(button -> button.updateDemand(hoverGeneration,
                demand.mayBake(button.modelHash(), nowMillis)));
        renderBackground(graphics);
        graphics.fillGradient(x, y, x + 135, y + 235, 0xFF222222, 0xFF222222);
        graphics.fillGradient(x + 138, y, x + 420, y + 235, 0xFF222222, 0xFF222222);
        graphics.fillGradient(x + 351, y + 7, x + 352, y + 21, 0xFFF3EFE0, 0xFFF3EFE0);
        textField.render(graphics, mouseX, mouseY, partialTick);
        renderReferenceEntity(graphics, mouseX, mouseY, minecraft.getFrameTime());

        if (textField.getValue().isEmpty() && !textField.isFocused()) {
            graphics.drawString(font, Component.translatable("gui.yes_steve_model.search")
                    .withStyle(ChatFormatting.ITALIC), x + 148, y + 10, 0x777777);
        }
        var page = "%d/%d".formatted(browser.page() + 1, browser.maxPage() + 1);
        graphics.drawString(font, page, x + 138 + (282 - font.width(page)) / 2,
                y + 223 - font.lineHeight / 2, 0xF3EFE0);
        var version = ModList.get().getModFileById(YesSteveModel.MOD_ID).versionString();
        graphics.drawString(font, version, x + 2, y + 226, ChatFormatting.DARK_GRAY.getColor());
        if (!browser.currentPack().isBlank()) {
            graphics.drawString(font, Component.literal("\uD83D\uDCC2 " + browser.currentPack())
                    .withStyle(ChatFormatting.GRAY), x + 142, y - 12, 0xF3EFE0);
        }
        if (service.loadingCount() > 0) {
            var loading = Component.literal(Integer.toString(service.loadingCount()));
            graphics.drawString(font, loading, x + 414 - font.width(loading), y + 218,
                    ChatFormatting.DARK_GRAY.getColor());
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        renderables.stream().filter(FlatIconButton.class::isInstance).map(FlatIconButton.class::cast)
                .forEach(button -> button.renderToolTip(graphics, this, mouseX, mouseY));
        modelButtons.forEach(button -> button.renderTooltip(graphics, this, mouseX, mouseY));
        failedButtons.forEach(button -> button.renderTooltip(graphics, this, mouseX, mouseY));
        renderables.stream().filter(PackButton.class::isInstance).map(PackButton.class::cast)
                .forEach(button -> button.renderComponentTooltip(graphics, this, mouseX, mouseY));
    }

    protected void renderReferenceEntity(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Window window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        RenderSystem.enableScissor((int) ((x + 5) * scale),
                (int) (window.getHeight() - ((y + 200) * scale)),
                (int) (125 * scale), (int) (171 * scale));
        InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x + 67, y + 190, 70,
                x + 67 - mouseX, y + 85 - mouseY, player);
        RenderSystem.disableScissor();

        player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(capability -> {
            var renderTarget = capability.getModelRenderTarget();
            var fallback = capability.getModelHash() == null ? "default" : service.displayPath(capability.getModelHash());
            var name = renderTarget == null ? ModelIdUtil.getFileNameFromPath(fallback)
                    : renderTarget.getDisplayName(ModelIdUtil.getFileNameFromPath(fallback));
            var lines = font.split(FormattedText.of(name), 125);
            var lineY = y + 205;
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, x + (135 - font.width(line)) / 2, lineY, 0xF3EFE0);
                lineY += 10;
            }
        });
    }

    @Override
    public void tick() {
        textField.tick();
        if (browser.catalog() != service.catalog()) {
            var previousPage = renderedPage;
            rebuildCatalog(service.catalog());
            calculateModelList();
            if (!previousPage.equals(pageSignature())) {
                demand.switchPage(Util.getMillis());
                init();
            }
        }
        maybeSubmitPage(Util.getMillis());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (textField.mouseClicked(mouseX, mouseY, button)) {
            setFocused(textField);
            return true;
        }
        if (textField.isFocused()) {
            textField.setFocused(false);
        }
        var handled = super.mouseClicked(mouseX, mouseY, button);
        if (!handled && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !browser.currentPack().isBlank()) {
            getMinecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
            backToParent();
            return true;
        }
        return handled;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        var previous = textField.getValue();
        if (textField.charTyped(codePoint, modifiers)) {
            if (!Objects.equals(previous, textField.getValue())) {
                demand.switchPage(Util.getMillis());
                browser.resetPage();
                init();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (PlayerModelScreenKey.PLAYER_MODEL_KEY.matches(keyCode, scanCode) && !textField.isFocused()) {
            onClose();
            return true;
        }
        var previous = textField.getValue();
        if (textField.keyPressed(keyCode, scanCode, modifiers)) {
            if (!Objects.equals(previous, textField.getValue())) {
                demand.switchPage(Util.getMillis());
                browser.resetPage();
                init();
            }
            return true;
        }
        return textField.isFocused() && textField.isVisible() && keyCode != 256
                || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void insertText(String text, boolean overwrite) {
        if (overwrite) {
            textField.setValue(text);
        } else {
            textField.insertText(text);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta != 0 && mouseX > x + 143 && mouseX < x + 430 && mouseY > y + 25 && mouseY < y + 235) {
            if (delta > 0 && browser.page() > 0) {
                demand.switchPage(Util.getMillis());
                browser.page(browser.page() - 1);
                init();
            } else if (delta < 0 && browser.page() < browser.maxPage()) {
                demand.switchPage(Util.getMillis());
                browser.page(browser.page() + 1);
                init();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        var search = textField == null ? "" : textField.getValue();
        super.resize(minecraft, width, height);
        textField.setValue(search);
    }

    @Override
    public void removed() {
        demand.close();
        closePage();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void closeModelButtons() {
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
        demand.switchPage(Util.getMillis());
        browser.backToParent();
        init();
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
        return Minecraft.getInstance().getLanguageManager().getSelected();
    }

}
