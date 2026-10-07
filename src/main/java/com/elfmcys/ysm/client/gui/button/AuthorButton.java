package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.client.gui.Screen;
import com.elfmcys.ysm.client.lang.LanguageManager;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.proto.mixel.manifest.info.Author;
import com.elfmcys.ysm.util.GuiRenderUtil;
import com.elfmcys.ysm.util.PlatformUtil;
import com.elfmcys.ysm.util.TextMeasureUtil;
import com.google.common.collect.Lists;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiConfirmOpenLink;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;

public class AuthorButton extends Button {
    private final Author author;
    private final ModelRenderTarget model;
    private ResourceLocation avatar;
    private final int index;
    private final List<String> tooltips;
    private int selectedContactIndex = -1;
    private final GuiScreen parent;

    public AuthorButton(int pX, int pY, Author author, ModelRenderTarget model,
                        ResourceLocation avatar, int index, GuiScreen parent) {
        super(pX, pY, 70, 130, "", b -> {
        });
        this.author = author;
        this.model = model;
        this.avatar = avatar;
        this.index = index;
        this.tooltips = Lists.newArrayList();
        if (this.author != null) {
            updateTooltips(false);
        }
        this.parent = parent;
    }

    public static AuthorButton empty(int pX, int pY, GuiScreen parent) {
        return new AuthorButton(pX, pY, null, null, null, -1, parent);
    }

    public void setAvatar(ResourceLocation avatar) {
        this.avatar = avatar;
    }

    @Override
    public void renderWidget(Minecraft mc, int mouseX, int mouseY, float partialTick) {
        FontRenderer font = mc.fontRenderer;
        if (author == null || model == null || avatar == null) {
            this.drawGradientRect(this.x, this.y, this.x + this.width, this.y + this.height, 0x8F_434242, 0x8F_434242);
            this.drawCenteredString(font, TextFormatting.GRAY + "......", this.x + this.width / 2, this.y + this.height / 2, 0xFFFFFFFF);
            return;
        }
        if (this.isMouseOver()) {
            this.drawGradientRect(this.x, this.y, this.x + this.width, this.y + this.height, 0x8F_306BAC, 0x8F_306BAC);
        } else {
            this.drawGradientRect(this.x, this.y, this.x + this.width, this.y + this.height, 0x8F_434242, 0x8F_434242);
        }
        mc.getTextureManager().bindTexture(avatar);
        drawScaledCustomSizeModalRect(this.x + 3, this.y + 3, 0, 0, 64, 64, 64, 64, 64, 64);

        String authorName = LanguageManager.getI18n(model, "metadata.authors.%d.name".formatted(index), author.name());
        String authorRole = LanguageManager.getI18n(model, "metadata.authors.%d.role".formatted(index), author.role());
        String authorComment = LanguageManager.getI18n(model, "metadata.authors.%d.comment".formatted(index),
                author.comment().orElse(""));

        GuiRenderUtil.drawScrollingString(font, TextFormatting.GOLD + authorName, this.x + 2, this.y + 72, this.x + this.width - 2, this.y + 82, 0xFFFFFFFF, true);
        this.drawCenteredString(font, TextFormatting.GREEN + authorRole, this.x + 35, this.y + 82, 0xFFFFFFFF);
        this.drawWordWrap(font, authorComment, this.x + 3, this.y + 95, 64, 0xFFFFFFFF);
    }

    public void drawWordWrap(FontRenderer font, String text, int x, int y, int lineWidth, int color) {
        for (String sequence : TextMeasureUtil.split(font, text, lineWidth)) {
            font.drawString(sequence, x, y, color, false);
            y += 9;
            if (y > this.y + this.height) {
                return;
            }
        }
    }

    public void renderToolTip(GuiScreen screen, int pMouseX, int pMouseY) {
        if (this.isMouseOver() && !tooltips.isEmpty()) {
            screen.drawHoveringText(tooltips, pMouseX, pMouseY);
        } else {
            if (selectedContactIndex != -1) {
                selectedContactIndex = -1;
                updateTooltips(false);
            }
        }
    }

    public boolean mouseScrolled(int pMouseX, int pMouseY, int pDelta) {
        if (!Screen.isMouseWithin(pMouseX, pMouseY, x, y, width, height)) {
            return false;
        }
        if (pDelta > 0) {
            if (selectedContactIndex > 0) {
                selectedContactIndex--;
                updateTooltips(false);
            }
            return true;
        } else if (pDelta < 0) {
            if (selectedContactIndex < tooltips.size() - 2) {
                selectedContactIndex++;
                updateTooltips(false);
            }
            return true;
        }
        return false;
    }

    private void updateTooltips(boolean copied) {
        if (author == null) {
            return;
        }

        tooltips.clear();
        for (int i = 0; i < author.contacts().size(); i++) {
            var contact = author.contacts().get(i);
            String component = contact.key() + ": " + contact.value_();
            if (i == selectedContactIndex) {
                component += TextFormatting.BOLD.toString() + TextFormatting.YELLOW + (copied ? " ✓" : " ◀");
            }
            tooltips.add(component);
        }
        if (!tooltips.isEmpty()) {
            tooltips.add(TextFormatting.DARK_GRAY + I18n.format("gui.yes_steve_model.model.info.contact.click_hint"));
        }
    }

    @Override
    public void onPress() {
        if (author == null) {
            return;
        }

        int index = selectedContactIndex;
        if (index == -1) {
            index = 0;
        }
        if (index < 0 || index >= author.contacts().size()) {
            return;
        }
        String value = author.contacts().get(index).value_();
        if (value == null) {
            return;
        }

        if (value.startsWith("http://") || value.startsWith("https://")) {
            Minecraft.getMinecraft().displayGuiScreen(new GuiConfirmOpenLink((yes, _) -> {
                if (yes) {
                    PlatformUtil.openLink(value);
                }
                Minecraft.getMinecraft().displayGuiScreen(parent);
            }, value, -1, true));
        } else {
            GuiScreen.setClipboardString(value);
            if (selectedContactIndex == -1) {
                selectedContactIndex = 0;
            }
            updateTooltips(true);
        }
    }
}
