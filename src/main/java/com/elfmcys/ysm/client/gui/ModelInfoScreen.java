package com.elfmcys.ysm.client.gui;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.gui.button.AuthorButton;
import com.elfmcys.ysm.client.gui.button.Button;
import com.elfmcys.ysm.client.gui.button.FlatColorButton;
import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.client.texture.CustomTexture;
import com.elfmcys.ysm.client.texture.CustomTextureManager;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetBatch;
import com.elfmcys.ysm.model.resource.client.asset.ModelAssetSelector;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.proto.mixel.manifest.info.Author;
import com.elfmcys.ysm.util.PlatformUtil;
import com.elfmcys.ysm.util.TextMeasureUtil;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiConfirmOpenLink;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

public class ModelInfoScreen extends Screen {
    private final static ResourceLocation DEFAULT_AVATAR = new ResourceLocation(YesSteveModel.MOD_ID, "texture/default_avatar.png");
    private final static Map<String, String> LINK_TYPE_PRESET = ImmutableMap.of(
            "home", I18n.format("gui.yes_steve_model.url.home"),
            "donate", I18n.format("gui.yes_steve_model.url.donate")
    );
    private final List<CustomTexture> loadedAvatars = new ArrayList<>();
    private final Map<Integer, AuthorButton> authorButtons = new HashMap<>();

    private final PlayerModelScreen parent;
    private final ModelRenderTarget model;
    private final ModelInfoView modelInfo;
    private int startAuthorIndex = 0;
    private ClientAssetBatch pageAssets;
    private int x;
    private int y;

    public ModelInfoScreen(PlayerModelScreen parent, ModelRenderTarget model) {
        super();
        this.parent = parent;
        this.model = model;
        this.modelInfo = model.info();
    }

    @Override
    public void onGuiClosed() {
        closePage();
        super.onGuiClosed();
    }

    @Override
    public void initGui() {
        closePage();
        pageAssets = ClientModelService.instance().createAssetBatch();

        this.x = (width - 420) / 2;
        this.y = (height - 235) / 2;

        var metadata = this.modelInfo.getMetadata();
        List<Author> authors = metadata == null
                ? List.of() : metadata.authors();
        if (authors.size() <= startAuthorIndex) {
            startAuthorIndex = 0;
        }

        for (int i = 0; i < 5; i++) {
            int index = startAuthorIndex + i;
            if (index >= authors.size()) {
                for (; i < 5; i++) {
                    addButton(AuthorButton.empty(this.x + 25 + 75 * i, this.y + 15, this));
                }
                continue;
            }
            var author = authors.get(index);
            var button = new AuthorButton(this.x + 25 + 75 * i, this.y + 15,
                    author, model, DEFAULT_AVATAR, index, this);
            authorButtons.put(index, button);
            addButton(button);
            requestAvatar(index, button, pageAssets);
        }
        pageAssets.submit();

        addButton(new FlatColorButton(x + 2, y + 25, 18, 100, "<", (b) -> {
            if (startAuthorIndex > 0) {
                startAuthorIndex = Math.max(0, startAuthorIndex - 5);
                this.refreshGui();
            }
        }).setTooltips("gui.yes_steve_model.pre_page"));
        addButton(new FlatColorButton(x + 25 + 75 * 5, y + 25, 18, 100, ">", (b) -> {
            if (startAuthorIndex + 5 < authors.size()) {
                startAuthorIndex = startAuthorIndex + 5;
                this.refreshGui();
            }
        }).setTooltips("gui.yes_steve_model.next_page"));

        int y = this.y + 150;
        for (var i = 0; i < Math.min(metadata.links().size(), 2); i++) {
            var link = metadata.links().get(i);
            var type = link.key();
            var value = link.value_();

            var displayText = LINK_TYPE_PRESET.get(type);
            if (displayText == null) {
                displayText = type;
            }

            addButton(new FlatColorButton(this.x + 310, y, 85, 20, displayText, b -> openUrl(value)));
            y += 25;
        }
        addButton(new FlatColorButton(this.x + 310, y, 85, 20, I18n.format("gui.yes_steve_model.model.return"), (b) -> {
            this.mc.displayGuiScreen(parent);
        }));
    }

    private void openUrl(@Nullable String homeUrl) {
        if (homeUrl != null && StringUtils.isNoneBlank(homeUrl)) {
            this.mc.displayGuiScreen(new GuiConfirmOpenLink((yes, _) -> {
                if (yes) {
                    PlatformUtil.openLink(homeUrl);
                }
                this.mc.displayGuiScreen(this);
            }, homeUrl, Button.ID, true));
        }
    }

    private void requestAvatar(int index, AuthorButton button, ClientAssetBatch assets) {
        var manifest = ClientModelService.instance().catalog().find(model.modelHash())
                .map(entry -> entry.displayRepresentation().view().getManifest()).orElse(null);
        var metadata = manifest == null ? null : manifest.info().metadataUnsafe();
        if (metadata == null || metadata.authors().isEmpty()
                || index >= metadata.authors().size()
                || !metadata.authors().get(index).hasAvatar()) {
            return;
        }
        assets.presentation(model.modelHash(),
                        ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR, index)
                .whenComplete((source, error) -> Minecraft.getMinecraft().addScheduledTask(() -> {
                    if (pageAssets != assets || authorButtons.get(index) != button) {
                        return;
                    }
                    if (source != null) {
                        var texture = ClientModelService.instance().createTexture(source);
                        loadedAvatars.add(texture);
                        button.setAvatar(CustomTextureManager.register(texture).id());
                    } else if (!isCancellation(error)) {
                        if (error == null) {
                            YesSteveModel.LOGGER.debug("Failed to load author avatar {}: unknown error", index);
                        } else {
                            YesSteveModel.LOGGER.debug("Failed to load author avatar {}", index, unwrap(error));
                        }
                    }
                }));
    }

    private void closePage() {
        var closingAssets = pageAssets;
        pageAssets = null;
        loadedAvatars.forEach(CustomTextureManager::release);
        loadedAvatars.clear();
        authorButtons.clear();
        if (closingAssets != null) {
            closingAssets.close();
        }
    }

    private static boolean isCancellation(@Nullable Throwable error) {
        return unwrap(error) instanceof CancellationException;
    }

    private static Throwable unwrap(@Nullable Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        drawDefaultBackground();

        this.drawGradientRect(this.x + 25, this.y + 150, this.x + 305, this.y + 220, 0x8F_5B5B5B, 0x8F_5B5B5B);

        var metadata = this.modelInfo.getMetadata();
        if (metadata != null) {
            String tips = LanguageManager.getI18n(model, "metadata.tips", metadata.tips().orElse(""));
            List<String> splitDesc = TextMeasureUtil.split(fontRenderer, tips, 270);
            int offset = 0;
            for (String desc : splitDesc) {
                this.drawString(fontRenderer, desc, this.x + 30, this.y + 154 + offset, 0xFFFFFFFF);
                offset += fontRenderer.FONT_HEIGHT;
                if (offset > fontRenderer.FONT_HEIGHT * 7) {
                    break;
                }
            }
        }
        super.drawScreen(mouseX, mouseY, partialTick);

        this.buttonList.stream().filter(r -> r instanceof AuthorButton)
                .forEach(r -> ((AuthorButton) r).renderToolTip(this, mouseX, mouseY));
    }

    @Override
    protected void mouseScrolled(int mouseX, int mouseY, int delta) {
        for (var guiButton : this.buttonList) {
            if (guiButton instanceof AuthorButton authorButton &&
                    authorButton.mouseScrolled(mouseX, mouseY, delta))
                return;
        }
        super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
