package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.parser.CapturedModel;
import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.format.vfs.ArchiveFileSystem;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class RawModelImporter {
    private final DefaultAnimationFilter defaultAnimations;

    public RawModelImporter(DefaultAnimationFilter defaultAnimations) {
        this.defaultAnimations = Objects.requireNonNull(defaultAnimations, "defaultAnimations");
    }

    public CapturedModel capture(Path source) {
        if (Files.isDirectory(source)) {
            try (var vfs = new Directory(source)) {
                return ModelParser.capture(vfs);
            }
        }
        try (var vfs = ArchiveFileSystem.open(source)) {
            return ModelParser.capture(vfs);
        }
    }

    public RawCompileResult convert(CapturedModel captured, Path outputDirectory) {
        Objects.requireNonNull(captured, "captured");
        var compiled = ModelParser.compile(
                captured.data().asVirtualFileSystem(), outputDirectory, defaultAnimations);
        if (!captured.modelId().equals(compiled.modelHash())) {
            throw new IllegalStateException(
                    "Captured and compiled model identities do not match");
        }
        if (!captured.diagnostics().equals(compiled.diagnostics())) {
            throw new IllegalStateException(
                    "Captured and compiled model diagnostics do not match");
        }
        return compiled;
    }
}
