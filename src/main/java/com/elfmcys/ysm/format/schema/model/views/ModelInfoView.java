package com.elfmcys.ysm.format.schema.model.views;

import com.elfmcys.ysm.format.schema.file.AssetFileView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.proto.mixel.common.StringPair;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTarget;
import com.elfmcys.ysm.proto.mixel.manifest.info.Author;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationButton;
import com.elfmcys.ysm.proto.mixel.manifest.info.ExtraAnimationClassify;
import com.elfmcys.ysm.proto.mixel.manifest.info.Info;
import com.elfmcys.ysm.proto.mixel.manifest.info.Metadata;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettings;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStats;
import com.elfmcys.ysm.proto.mixel.manifest.info.Settings;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Immutable manifest metadata plus rebuildable lookup indexes. */
public final class ModelInfoView {
    private final AssetFileView view;
    private final Info info;
    private final Settings settings;
    private final RenderTarget player;
    private final Map<String, Map<String, String>> langs;
    private final HashMap<String, Author> authors;
    private final Map<String, ExtraAnimationButton> extraAnimationButtons;
    private final Map<String, ExtraAnimationClassify> extraAnimationClassifications;

    public ModelInfoView(Info info,
                         RenderTarget player,
                         AssetFileView view) {
        this.view = Objects.requireNonNull(view, "view");
        this.info = Objects.requireNonNull(info, "info");
        this.settings = info.settings();
        this.player = Objects.requireNonNull(player, "player");

        var languages = new HashMap<String, Map<String, String>>(info.languageFiles().size());
        if (!info.languageFiles().isEmpty()) {
            for (var langFile : info.languageFiles()) {
                var lang = new HashMap<String, String>(
                        langFile.entries().size());
                if (!langFile.entries().isEmpty()) {
                    for (var item : langFile.entries().object2ObjectEntrySet()) {
                        lang.put(item.getKey(), item.getValue());
                    }
                }
                languages.put(langFile.locale(), Map.copyOf(lang));
            }
        }
        langs = Map.copyOf(languages);

        var metadata = info.metadataUnsafe();
        var hasAuthors = metadata != null && !metadata.authors().isEmpty();
        authors = new HashMap<>(hasAuthors ? metadata.authors().size() : 0);
        if (hasAuthors) {
            for (var author : metadata.authors()) {
                authors.put(author.name(), author);
            }
        }

        var buttons = new LinkedHashMap<String, ExtraAnimationButton>();
        for (var button : settings.extraAnimationButtons()) {
            for (var form : button.configForms()) {
                if (!form.type().equals("checkbox")
                        && !form.type().equals("radio")
                        && !form.type().equals("range")) {
                    throw new IllegalArgumentException(
                            "Unknown extra-animation form type: " + form.type());
                }
            }
            buttons.put(button.id(), button);
        }
        extraAnimationButtons = Map.copyOf(buttons);

        var classifications = new LinkedHashMap<String, ExtraAnimationClassify>();
        for (var classification : settings.extraAnimationClassify()) {
            classifications.put(classification.id(), classification);
        }
        extraAnimationClassifications = Map.copyOf(classifications);
    }

    @NotNull
    public String translateOr(String key, String locale, @Nullable String defaultValue) {
        // Historical and externally supplied manifests may omit a scalar fallback.  The
        // catalog UI must still be renderable when both the translation and that field are
        // absent; an empty string is the safe display value.
        return Objects.requireNonNullElse(translate(key, locale),
                Objects.requireNonNullElse(defaultValue, ""));
    }

    @Nullable
    public String translate(String key, String locale) {
        var value = translateInner(key, locale);
        if (value != null) {
            return value;
        }
        return translateInner(key, "en_us");
    }

    @Nullable
    private String translateInner(String key, String locale) {
        var lang = langs.get(locale);
        if (lang != null) {
            return lang.get(key);
        }
        return null;
    }

    public @Nullable Metadata getMetadata() {
        return info.metadataUnsafe();
    }

    public boolean hasMetadata() {
        return info.hasMetadata();
    }

    public Settings getSettings() {
        return settings;
    }

    public ModelSettings getPlayerSettings() {
        return player.settings();
    }

    public ModelStats getPlayerStats() {
        return player.stats();
    }

    public ObjectList<StringPair> getExtraAnimations() {
        return settings.extraAnimation();
    }

    public Map<String, ExtraAnimationButton> getExtraAnimationButtons() {
        return extraAnimationButtons;
    }

    public Map<String, ExtraAnimationClassify> getExtraAnimationClassifications() {
        return extraAnimationClassifications;
    }

    public @Nullable Image readAvatar(BooleanSupplier cancelled, ChunkDataSource source,
                                      String authorName) throws IOException {
        var author = authors.get(authorName);
        if (author == null || !author.hasAvatar()) {
            return null;
        }
        return view.readImageBlob(cancelled, source, author.avatarUnsafe());
    }
}
