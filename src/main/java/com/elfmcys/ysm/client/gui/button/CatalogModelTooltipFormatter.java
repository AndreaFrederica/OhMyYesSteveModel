package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.model.catalog.client.entry.CatalogModelMetadata;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.util.ModelIdUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import net.minecraft.ChatFormatting;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

/** Formats the complete catalog-card tooltip without depending on GUI state. */
public final class CatalogModelTooltipFormatter {
    public static final int MAX_WIDTH = 240;
    static final int MAX_AUTHORS = 5;

    private static final String KEY_PREFIX = "gui.yes_steve_model.catalog.tooltip.";

    private CatalogModelTooltipFormatter() {
    }

    public static Input input(CatalogModelMetadata catalog, ClientCatalogEntry entry,
                              String locale, @Nullable Throwable loadError) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(locale, "locale");

        var info = catalog.info();
        var metadata = info.getMetadata();
        var name = displayName(catalog, locale);
        var tips = metadata == null ? ""
                : catalog.localized(locale, "metadata.tips", metadata.tips().orElse(""));
        var authors = new ArrayList<Author>();
        var license = "";
        if (metadata != null) {
            for (var index = 0; index < metadata.authors().size(); index++) {
                var author = metadata.authors().get(index);
                var authorName = catalog.localized(locale,
                        "metadata.authors.%d.name".formatted(index), author.name()).trim();
                if (authorName.isEmpty()) {
                    continue;
                }
                var role = catalog.localized(locale,
                        "metadata.authors.%d.role".formatted(index), author.role()).trim();
                authors.add(new Author(authorName, role));
            }
            var modelLicense = metadata.license();
            license = catalog.localized(locale, "metadata.license.type",
                    StringUtils.firstNonBlank(modelLicense.type(), modelLicense.desc().orElse(""), ""));
        }
        var stats = catalog.info().getPlayerStats();
        // MC manifests keep their texture ids on the render target.  General
        // mesh manifests intentionally do not (materials live in the scene
        // package), so the importer stores the decoded image count in stats.
        var textureCount = stats.textures() > 0
                ? stats.textures()
                : catalog.representation().view().getPlayer().getTextureNames().size();
        return new Input(name, tips, authors, license, catalog.path(), entry.modelHash().toString(),
                source(entry), new Stats(stats.bones(), stats.cubes(), stats.faces(), textureCount), loadError);
    }

    public static String displayName(CatalogModelMetadata catalog, String locale) {
        var info = catalog.info();
        var metadata = info.getMetadata();
        var fallback = ModelIdUtil.getFileNameFromPath(catalog.path());
        if (metadata == null || StringUtils.isBlank(metadata.name())) {
            return fallback;
        }
        return StringUtils.firstNonBlank(
                catalog.localized(locale, "metadata.name", metadata.name()), fallback);
    }

    public static List<Line> format(Input input, boolean detailed, Translator translator) {
        return format(input, detailed, translator, "en_us");
    }

    public static List<Line> format(Input input, boolean detailed, Translator translator, String locale) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(translator, "translator");
        Objects.requireNonNull(locale, "locale");
        var lines = new ArrayList<Line>();
        lines.add(new Line(input.name(), ChatFormatting.GOLD));
        appendTips(lines, input.name(), input.tips());
        appendMetadata(lines, input, translator);
        if (input.loadError() != null) {
            lines.add(new Line(translator.translate(KEY_PREFIX + "error",
                    errorSummary(input.loadError(), locale)), ChatFormatting.RED));
        }
        if (detailed) {
            appendDetails(lines, input, translator, locale);
        }
        appendStats(lines, input.stats(), translator);
        return List.copyOf(lines);
    }

    private static void appendTips(List<Line> lines, String name, String tips) {
        var normalizedName = normalize(name);
        for (var value : tips.replace("\r", "").split("\n", -1)) {
            var line = value.trim();
            if (!line.isEmpty() && !normalize(line).equals(normalizedName)) {
                lines.add(new Line(line, ChatFormatting.GRAY));
            }
        }
    }

    private static void appendMetadata(List<Line> lines, Input input, Translator translator) {
        var firstMetadataLine = lines.size();
        if (!input.authors().isEmpty()) {
            var visible = input.authors().stream().limit(MAX_AUTHORS)
                    .map(author -> author.role().isEmpty() ? author.name()
                            : translator.translate(KEY_PREFIX + "author_role", author.role(), author.name()))
                    .toList();
            var authors = String.join(", ", visible);
            var remaining = input.authors().size() - visible.size();
            if (remaining > 0) {
                authors += " " + translator.translate(KEY_PREFIX + "authors_more", remaining);
            }
            lines.add(new Line(translator.translate(KEY_PREFIX + "authors", authors), ChatFormatting.GRAY));
        }
        if (!input.license().isBlank()) {
            lines.add(new Line(translator.translate(KEY_PREFIX + "license", input.license()),
                    ChatFormatting.GRAY));
        }
        if (lines.size() > firstMetadataLine) {
            lines.add(firstMetadataLine, Line.SPACE);
        }
    }

    private static void appendDetails(List<Line> lines, Input input, Translator translator, String locale) {
        lines.add(Line.SPACE);
        lines.add(detail(translator, "path", input.path()));
        lines.add(detail(translator, "hash", input.hash()));
        lines.add(detail(translator, "source",
                translator.translate(KEY_PREFIX + "source." + input.source().key)));
        if (input.loadError() != null) {
            lines.add(detail(translator, "root_cause", rootCause(input.loadError(), locale)));
        }
    }

    private static void appendStats(List<Line> lines, Stats stats, Translator translator) {
        lines.add(Line.SPACE);
        lines.add(new Line(translator.translate(KEY_PREFIX + "stats",
                stats.bones(), stats.cubes(), stats.faces()), ChatFormatting.GOLD));
        lines.add(new Line(translator.translate(KEY_PREFIX + "textures", stats.textures()),
                ChatFormatting.GOLD));
    }

    private static Line detail(Translator translator, String key, Object... arguments) {
        return new Line(translator.translate(KEY_PREFIX + key, arguments), ChatFormatting.DARK_GRAY);
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** A short, user-facing diagnostic that preserves the useful inner failure. */
    static String errorSummary(Throwable error, String locale) {
        var messages = messages(error);
        var chinese = locale.toLowerCase(Locale.ROOT).startsWith("zh");
        var dependency = messages.stream().filter(value -> value.contains("Package dependency not found"))
                .findFirst().orElse(null);
        if (dependency != null) {
            var detail = dependency.substring(dependency.indexOf(':') + 1).trim();
            return chinese ? "缺少资源：" + detail : "Missing resource: " + detail;
        }
        var image = messages.stream().filter(value -> value.contains("Decoded scene image exceeds"))
                .findFirst().orElse(null);
        if (image != null) {
            var key = messages.stream().filter(value -> value.startsWith("Key["))
                    .findFirst().orElse(null);
            if (key != null) {
                return chinese ? "纹理解码超出预算：" + key : "Texture decode budget exceeded: " + key;
            }
            return chinese ? "纹理解码超出内存预算" : "Texture decode budget exceeded";
        }
        var physics = messages.stream().filter(value -> value.contains("Physics value outside [0,1]"))
                .findFirst().orElse(null);
        if (physics != null) {
            return chinese ? "物理参数超出 [0,1] 范围" : physics;
        }
        var first = messages.isEmpty() ? "" : messages.get(0);
        if (!first.isEmpty() && !isGenericFailure(first)) {
            return first;
        }
        var best = messages.stream().filter(value -> !isGenericFailure(value)).findFirst().orElse(null);
        return StringUtils.firstNonBlank(best, unwrap(error).getClass().getSimpleName(), "Unknown error");
    }

    private static String rootCause(Throwable error, String locale) {
        var cause = unwrap(error);
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (cause.getCause() != null && cause.getCause() != cause && visited.add(cause)) {
            cause = cause.getCause();
        }
        var message = StringUtils.firstNonBlank(cause.getMessage(), "");
        return message.isEmpty() ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }

    private static List<String> messages(Throwable error) {
        var result = new ArrayList<String>();
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (var current = unwrap(error); current != null && visited.add(current); current = current.getCause()) {
            var message = StringUtils.firstNonBlank(current.getMessage(), "").trim();
            if (!message.isEmpty() && !result.contains(message)) {
                result.add(message.replace('\n', ' ').replace('\r', ' '));
            }
        }
        return result;
    }

    private static boolean isGenericFailure(String value) {
        return value.equals("Failed to load model render target")
                || value.equals("Failed to build model render target")
                || value.equals("Unknown error");
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static Source source(ClientCatalogEntry entry) {
        return switch (entry.origin()) {
            case SERVER -> Source.SERVER;
            case BUILTIN -> Source.BUILTIN;
            case CUSTOM -> Source.CUSTOM;
            case AUTH -> Source.AUTH;
        };
    }

    public record Input(String name, String tips, List<Author> authors, String license,
                        String path, String hash, Source source, Stats stats,
                        @Nullable Throwable loadError) {
        public Input {
            name = Objects.requireNonNull(name, "name").trim();
            tips = Objects.requireNonNullElse(tips, "");
            authors = List.copyOf(authors);
            license = Objects.requireNonNullElse(license, "").trim();
            path = Objects.requireNonNull(path, "path");
            hash = Objects.requireNonNull(hash, "hash");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(stats, "stats");
        }
    }

    public record Author(String name, String role) {
        public Author {
            name = Objects.requireNonNull(name, "name").trim();
            role = Objects.requireNonNullElse(role, "").trim();
        }
    }

    public record Stats(int bones, int cubes, int faces, int textures) {
    }

    public record Line(String text, ChatFormatting color) {
        static final Line SPACE = new Line(" ", ChatFormatting.GRAY);

        public Line {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(color, "color");
        }
    }

    public enum Source {
        BUILTIN("builtin"),
        CUSTOM("custom"),
        AUTH("auth"),
        SERVER("server");

        private final String key;

        Source(String key) {
            this.key = key;
        }
    }

    @FunctionalInterface
    public interface Translator {
        String translate(String key, Object... arguments);
    }
}
