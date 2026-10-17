package com.elfmcys.ysm.format.parser;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.cpm.CpmCompiledModel;
import cc.sirrus.ysmlib.cpm.CpmDocument;
import cc.sirrus.ysmlib.scene.ByteData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Packs CPM's independently addressable texture files into the single image
 * understood by the YSM baked geometry format.  UVs remain in the source
 * texture's pixel coordinate system; the adapter adds the region origin and
 * changes the geometry texture dimensions to the atlas dimensions.
 */
public final class CpmTextureAtlas {
    private static final String[] IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".bmp"};

    private CpmTextureAtlas() {}

    public static Result build(CpmDocument source) throws IOException {
        Objects.requireNonNull(source, "source");
        var compiled = YsmRuntime.cpm().compileCanonical(source);
        return build(source, compiled);
    }

    static Result build(CpmDocument source, CpmCompiledModel compiled) throws IOException {
        var images = new LinkedHashMap<String, BufferedImage>();
        var aliases = new LinkedHashMap<String, String>();
        try {
            source.files().entrySet().stream()
                    .filter(entry -> isImage(entry.getKey()))
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        try {
                            var image = ImageIO.read(new ByteArrayInputStream(entry.getValue().copy()));
                            if (image == null) throw new IOException("Unsupported image: " + entry.getKey());
                            String path = normalize(entry.getKey());
                            images.putIfAbsent(path, image);
                            addAliases(aliases, path);
                        } catch (IOException failure) {
                            throw new AtlasIOException(failure);
                        }
                    });
        } catch (AtlasIOException failure) {
            throw (IOException) failure.getCause();
        }
        if (images.isEmpty()) {
            var placeholder = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            placeholder.setRGB(0, 0, 0);
            images.put("__cpm_placeholder.png", placeholder);
            aliases.put("__cpm_placeholder", "__cpm_placeholder.png");
        }

        int cellWidth = Math.max(64, compiled.textureWidth());
        int cellHeight = Math.max(64, compiled.textureHeight());
        var symbols = new ArrayList<String>();
        for (var primitive : compiled.primitives()) {
            if (primitive.texture() != null && !primitive.texture().isBlank()) symbols.add(primitive.texture());
        }
        for (var texture : compiled.textures().keySet()) {
            if (texture != null && !texture.isBlank()) symbols.add(texture);
        }
        // Keep unreferenced images available for legacy texture expressions and
        // diagnostics, while making the layout deterministic.
        for (String path : images.keySet()) symbols.add(path);
        var selected = new LinkedHashMap<String, BufferedImage>();
        String first = images.keySet().iterator().next();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank() || selected.containsKey(symbol)) continue;
            String path = resolve(aliases, symbol);
            selected.put(symbol, images.getOrDefault(path, images.get(first)));
        }
        if (selected.isEmpty()) selected.put(first, images.get(first));

        var regions = new LinkedHashMap<String, Region>();
        int width = 0;
        for (var entry : selected.entrySet()) {
            var image = entry.getValue();
            int w = Math.max(cellWidth, image.getWidth());
            int h = Math.max(cellHeight, image.getHeight());
            regions.put(entry.getKey(), new Region(width, 0, w, h, image.getWidth(), image.getHeight(), cellWidth, cellHeight));
            width = Math.addExact(width, w);
        }
        int height = selected.values().stream().mapToInt(image -> Math.max(cellHeight, image.getHeight())).max().orElse(cellHeight);
        var atlas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = atlas.createGraphics();
        try {
            for (var entry : selected.entrySet()) {
                var region = regions.get(entry.getKey());
                graphics.drawImage(entry.getValue(), region.u(), region.v(), null);
            }
        } finally {
            graphics.dispose();
        }
        var png = new ByteArrayOutputStream();
        if (!ImageIO.write(atlas, "png", png)) throw new IOException("PNG encoder unavailable");
        return new Result(new Layout(width, height, regions, regions.values().iterator().next()), png.toByteArray());
    }

    private static boolean isImage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String extension : IMAGE_EXTENSIONS) if (lower.endsWith(extension)) return true;
        return false;
    }

    private static String normalize(String path) { return path.replace('\\', '/'); }

    private static void addAliases(Map<String, String> aliases, String path) {
        String stem = path.substring(0, path.lastIndexOf('.'));
        String leaf = stem.substring(stem.lastIndexOf('/') + 1);
        putAlias(aliases, stem, path);
        putAlias(aliases, leaf, path);
        int dot = leaf.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < leaf.length()) putAlias(aliases, leaf.substring(dot + 1), path);
        putAlias(aliases, "tex." + leaf, path);
        putAlias(aliases, "tex." + (dot >= 0 ? leaf.substring(dot + 1) : leaf), path);
    }

    private static void putAlias(Map<String, String> aliases, String alias, String path) {
        aliases.putIfAbsent(alias, path);
        aliases.putIfAbsent(alias.toLowerCase(Locale.ROOT), path);
    }

    private static String resolve(Map<String, String> aliases, String symbol) {
        String result = aliases.get(symbol);
        if (result == null) result = aliases.get(symbol.toLowerCase(Locale.ROOT));
        if (result == null && symbol.startsWith("tex.")) result = aliases.get(symbol.substring(4));
        return result;
    }

    public record Region(int u, int v, int width, int height, int imageWidth, int imageHeight,
                         int logicalWidth, int logicalHeight) {
        public Region(int u, int v, int width, int height) { this(u, v, width, height, width, height, width, height); }
    }

    public record Layout(int width, int height, Map<String, Region> regions, Region fallback) {
        public Layout {
            regions = Map.copyOf(regions);
            Objects.requireNonNull(fallback, "fallback");
        }

        public Region region(String symbol) {
            if (symbol == null || symbol.isBlank()) return fallback;
            return regions.getOrDefault(symbol, regions.getOrDefault(symbol.toLowerCase(Locale.ROOT), fallback));
        }
    }

    public record Result(Layout layout, byte[] png) {
        public Result {
            Objects.requireNonNull(layout, "layout");
            png = png.clone();
        }
        public byte[] png() { return png.clone(); }
    }

    private static final class AtlasIOException extends RuntimeException {
        private AtlasIOException(IOException cause) { super(cause); }
    }
}
