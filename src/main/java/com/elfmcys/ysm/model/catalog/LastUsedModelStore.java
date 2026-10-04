package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.model.domain.Hash256;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Properties;

/** Small client preference used to admit the last selected local source first. */
public final class LastUsedModelStore {
    private static final String PATH = "path";
    private static final String MODEL_ID = "modelId";
    private final Path file;

    public LastUsedModelStore(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
    }

    public Preference read() {
        if (!Files.isRegularFile(file)) return Preference.EMPTY;
        var properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            var path = properties.getProperty(PATH, "").trim().replace('\\', '/');
            var modelId = properties.getProperty(MODEL_ID, "").trim();
            if (path.isEmpty() || path.startsWith("/") || path.contains("..")) {
                return Preference.EMPTY;
            }
            Hash256 hash = modelId.isEmpty() ? null : Hash256.parse(modelId);
            return new Preference(path, hash);
        } catch (IOException | RuntimeException ignored) {
            return Preference.EMPTY;
        }
    }

    public synchronized void write(String path, Hash256 modelId) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(modelId, "modelId");
        var normalized = path.trim().replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/") || normalized.contains("..")) {
            throw new IllegalArgumentException("Invalid last-used model path: " + path);
        }
        var properties = new Properties();
        properties.setProperty(PATH, normalized);
        properties.setProperty(MODEL_ID, modelId.toString());
        try {
            Files.createDirectories(file.getParent());
            var temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                properties.store(output, "Oh My YSM last selected model");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            // A preference must never make model selection fail.
        }
    }

    public record Preference(String path, Hash256 modelId) {
        public static final Preference EMPTY = new Preference("", null);

        public Preference {
            Objects.requireNonNull(path, "path");
        }

        public boolean matches(String sourcePath) {
            return !path.isEmpty() && path.equals(sourcePath.replace('\\', '/'));
        }

        public boolean present() {
            return !path.isEmpty();
        }
    }
}
