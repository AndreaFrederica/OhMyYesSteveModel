package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;

import java.util.Objects;

/** The only persisted converted-source mapping. */
public record ConvertedSourceIndex(ModelFileIdentity identity,
                                   String rawRelativePath, String fullModVersion,
                                   long sourceSize, long sourceLastModifiedMillis,
                                   String sourceFileKey) {
    /** Legacy index entry without a metadata stamp; it falls back to content hashing once. */
    public ConvertedSourceIndex(ModelFileIdentity identity, String rawRelativePath,
                                String fullModVersion) {
        this(identity, rawRelativePath, fullModVersion, -1, -1, "");
    }

    public ConvertedSourceIndex {
        Objects.requireNonNull(identity, "identity");
        rawRelativePath = normalizeRawRelativePath(rawRelativePath);
        fullModVersion = Objects.requireNonNull(fullModVersion, "fullModVersion");
        if (fullModVersion.isBlank()) {
            throw new IllegalArgumentException("Full mod version must not be blank");
        }
        if (sourceSize < -1 || sourceLastModifiedMillis < -1) {
            throw new IllegalArgumentException("Source stamp values must be -1 or non-negative");
        }
        sourceFileKey = Objects.requireNonNullElse(sourceFileKey, "");
    }

    public boolean hasSourceStamp() {
        return sourceSize >= 0 && sourceLastModifiedMillis >= 0;
    }

    public Hash256 modelId() {
        return identity.modelId();
    }

    public Hash256 containerId() {
        return identity.containerId();
    }

    static String normalizeRawRelativePath(String value) {
        Objects.requireNonNull(value, "rawRelativePath");
        if (value.isEmpty() || value.startsWith("/") || value.endsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid raw relative path: " + value);
        }
        for (var segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid raw relative path: " + value);
            }
        }
        return value;
    }
}
