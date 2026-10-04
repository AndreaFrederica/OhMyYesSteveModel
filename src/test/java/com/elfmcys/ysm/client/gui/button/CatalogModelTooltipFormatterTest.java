package com.elfmcys.ysm.client.gui.button;

import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogModelTooltipFormatterTest {
    private static final String PREFIX = "gui.yes_steve_model.catalog.tooltip.";
    private static final CatalogModelTooltipFormatter.Translator TRANSLATOR =
            CatalogModelTooltipFormatterTest::translate;

    @Test
    void summaryLocalizesMetadataAndLimitsAuthors() {
        var input = input(new CompletionException(new IllegalStateException(
                "preview failed", new IOException("missing chunk"))));

        var lines = CatalogModelTooltipFormatter.format(input, false, TRANSLATOR);
        var text = lines.stream().map(CatalogModelTooltipFormatter.Line::text).toList();

        assertEquals(List.of(
                "Localized Name",
                "First line",
                "Second line",
                " ",
                "Authors: Role 0: Author 0, Author 1, Role 2: Author 2, Author 3, Role 4: Author 4 (+2 more)",
                "License: CC-BY-4.0",
                "Load error: preview failed",
                " ",
                "Stats: 12 bones, 34 cubes, 56 faces",
                "Textures: 7"), text);
        assertEquals(ChatFormatting.GOLD, lines.get(0).color());
        assertEquals(ChatFormatting.RED, lines.get(6).color());
        assertEquals(ChatFormatting.GOLD, lines.get(lines.size() - 1).color());
        assertFalse(text.stream().anyMatch(value -> value.contains("catalog/path")));
    }

    @Test
    void shiftAddsDiagnosticsAndDeepestRootCause() {
        var input = input(new IllegalStateException("preview failed", new IOException("missing chunk")));

        var lines = CatalogModelTooltipFormatter.format(input, true, TRANSLATOR);
        var text = lines.stream().map(CatalogModelTooltipFormatter.Line::text).toList();

        assertTrue(text.contains("Path: catalog/path"));
        assertTrue(text.contains("Hash: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertTrue(text.contains("Source: Built-in"));
        assertTrue(text.contains("Stats: 12 bones, 34 cubes, 56 faces"));
        assertTrue(text.contains("Textures: 7"));
        assertTrue(text.contains("Root cause: IOException: missing chunk"));
        assertEquals(ChatFormatting.DARK_GRAY,
                lines.get(text.indexOf("Root cause: IOException: missing chunk")).color());
    }

    @Test
    void duplicateTipsAreRemovedPerExplicitLine() {
        var input = new CatalogModelTooltipFormatter.Input(
                "Same Name", " same   name \nUseful detail\nSAME NAME", List.of(), "",
                "path", "hash", CatalogModelTooltipFormatter.Source.CUSTOM,
                new CatalogModelTooltipFormatter.Stats(0, 0, 0, 0), null);

        var lines = CatalogModelTooltipFormatter.format(input, false, TRANSLATOR);

        assertEquals(List.of("Same Name", "Useful detail", " ",
                        "Stats: 0 bones, 0 cubes, 0 faces", "Textures: 0"),
                lines.stream().map(CatalogModelTooltipFormatter.Line::text).toList());
    }

    @Test
    void diagnosticsPreferTheInnerAssetFailure() {
        var missingTexture = new IOException("Package dependency not found: textures/髪.png (textures/髪.png)");
        var wrapped = new IOException("Failed to load model render target", missingTexture);
        assertEquals("缺少资源：textures/髪.png (textures/髪.png)",
                CatalogModelTooltipFormatter.errorSummary(wrapped, "zh_cn"));

        var image = new IOException("Key[owner=vertin.pmx, index=4, reference=, domain=MODEL]",
                new IOException("Decoded scene image exceeds pixel/byte budget"));
        assertEquals("Texture decode budget exceeded: Key[owner=vertin.pmx, index=4, reference=, domain=MODEL]",
                CatalogModelTooltipFormatter.errorSummary(image, "en_us"));

        assertEquals("物理参数超出 [0,1] 范围", CatalogModelTooltipFormatter.errorSummary(
                new IllegalArgumentException("Physics value outside [0,1]"), "zh_cn"));
    }

    private static CatalogModelTooltipFormatter.Input input(Throwable error) {
        var authors = new ArrayList<CatalogModelTooltipFormatter.Author>();
        for (var index = 0; index < 7; index++) {
            authors.add(new CatalogModelTooltipFormatter.Author(
                    "Author " + index, index % 2 == 0 ? "Role " + index : ""));
        }
        return new CatalogModelTooltipFormatter.Input(
                "Localized Name", "Localized Name\nFirst line\r\n\nSecond line", authors,
                "CC-BY-4.0", "catalog/path",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                CatalogModelTooltipFormatter.Source.BUILTIN,
                new CatalogModelTooltipFormatter.Stats(12, 34, 56, 7), error);
    }

    private static String translate(String key, Object... arguments) {
        var translations = new HashMap<>(Map.ofEntries(
                Map.entry(PREFIX + "authors", "Authors: %s"),
                Map.entry(PREFIX + "author_role", "%1$s: %2$s"),
                Map.entry(PREFIX + "authors_more", "(+%s more)"),
                Map.entry(PREFIX + "license", "License: %s"),
                Map.entry(PREFIX + "error", "Load error: %s"),
                Map.entry(PREFIX + "path", "Path: %s"),
                Map.entry(PREFIX + "hash", "Hash: %s"),
                Map.entry(PREFIX + "source", "Source: %s"),
                Map.entry(PREFIX + "stats", "Stats: %1$s bones, %2$s cubes, %3$s faces"),
                Map.entry(PREFIX + "root_cause", "Root cause: %s"),
                Map.entry(PREFIX + "source.builtin", "Built-in"),
                Map.entry(PREFIX + "source.custom", "Local"),
                Map.entry(PREFIX + "source.auth", "Authorized"),
                Map.entry(PREFIX + "source.server", "Game server"),
                Map.entry(PREFIX + "textures", "Textures: %s")));
        return translations.getOrDefault(key, key).formatted(arguments);
    }
}
