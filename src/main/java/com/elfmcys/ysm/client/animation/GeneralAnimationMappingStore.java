package com.elfmcys.ysm.client.animation;

import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.domain.Hash256;
import cc.sirrus.ysmlib.scene.ScenePackagePlayback;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Local authoring aliases. Network requests always resolve these to a published scene action. */
public final class GeneralAnimationMappingStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static GeneralAnimationMappingStore instance;
    private final Path path;
    private Map<String, Map<String, Entry>> mappings = new LinkedHashMap<>();
    private long revision;
    private boolean readable = true;
    public record Entry(String sourceId, int clip, boolean loop) {
        public Entry { new ScenePackagePlayback.Selection(Objects.requireNonNull(sourceId), clip); }
        public ScenePackagePlayback.Selection selection() { return new ScenePackagePlayback.Selection(sourceId, clip); }
    }
    public GeneralAnimationMappingStore(Path path) {
        this.path = path;
        if (!Files.exists(path)) return;
        try (var reader = Files.newBufferedReader(path)) {
            var root = JSON.fromJson(reader, Root.class);
            if (root == null || root.models == null) throw new IOException("Missing models map");
            var validated = new LinkedHashMap<String, Map<String, Entry>>();
            for (var model : root.models.entrySet()) {
                Hash256.parse(model.getKey());
                var row = new LinkedHashMap<String, Entry>();
                for (var entry : model.getValue().entrySet()) {
                    checkName(entry.getKey());Objects.requireNonNull(entry.getValue()).selection();row.put(entry.getKey(), entry.getValue());
                }
                validated.put(model.getKey(), Map.copyOf(row));
            }
            mappings = validated;
        } catch (IOException | RuntimeException failure) {
            readable = false;
            YesSteveModel.LOGGER.error("Cannot read general animation mappings {}; original file retained", path, failure);
        }
    }
    public static synchronized GeneralAnimationMappingStore instance() {
        if (instance == null) instance = new GeneralAnimationMappingStore(AssetPaths.configRoot().resolve("general-animation-mappings.json"));
        return instance;
    }
    public synchronized Map<String, Entry> entries(Hash256 model) { return Map.copyOf(mappings.getOrDefault(model.toString(), Map.of())); }
    public synchronized long version() { return revision; }
    public synchronized void put(Hash256 model, String name, Entry value) throws IOException {
        checkName(name);
        if (!readable) throw new IOException("Mapping file is damaged; repair or move it before saving: " + path);
        var next = new LinkedHashMap<>(mappings);
        var row = new LinkedHashMap<>(next.getOrDefault(model.toString(), Map.of()));
        if (value == null) row.remove(name);else { value.selection();row.put(name, value); }
        if (row.isEmpty()) next.remove(model.toString());else next.put(model.toString(), Map.copyOf(row));
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "animation-mappings-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { JSON.toJson(new Root(next), writer); }
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            mappings = next;revision++;
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void checkName(String name) {
        if (name == null || name.isBlank() || name.length() > 128 || name.startsWith("scene/") || name.startsWith("#"))
            throw new IllegalArgumentException("Invalid animation alias");
    }
    public static Optional<ScenePackagePlayback.Selection> get(Hash256 model, String state) {
        return Optional.ofNullable(instance().entries(model).get(state)).map(Entry::selection);
    }
    public static long revision() { return instance().version(); }
    private record Root(Map<String, Map<String, Entry>> models) {}
}
