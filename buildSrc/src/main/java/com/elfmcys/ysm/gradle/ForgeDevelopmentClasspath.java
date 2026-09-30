package com.elfmcys.ysm.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Removes stale signing metadata from transformed Forge development jars in isolated JVMs. */
public final class ForgeDevelopmentClasspath {
    private ForgeDevelopmentClasspath() {
    }

    public static List<File> unsigned(Collection<File> classpath, Path workDirectory,
                                      Consumer<String> onUnsigned) throws IOException {
        var result = new ArrayList<File>();
        var index = 0;
        for (var file : classpath) {
            if (!file.isFile() || !file.getName().startsWith("forge-")
                    || !file.getName().endsWith(".jar")) {
                result.add(file);
                continue;
            }
            try (var archive = new ZipFile(file)) {
                if (archive.stream().noneMatch(entry -> isSignature(entry.getName()))) {
                    result.add(file);
                    continue;
                }
                Files.createDirectories(workDirectory);
                var unsigned = workDirectory.resolve("unsigned-forge-" + index++ + ".jar");
                try (var output = new ZipOutputStream(Files.newOutputStream(unsigned))) {
                    var entries = archive.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (isSignature(entry.getName())
                                || entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                            continue;
                        }
                        output.putNextEntry(new ZipEntry(entry.getName()));
                        try (var input = archive.getInputStream(entry)) {
                            input.transferTo(output);
                        }
                        output.closeEntry();
                    }
                }
                onUnsigned.accept(file.getName());
                result.add(unsigned.toFile());
            }
        }
        return result;
    }

    private static boolean isSignature(String name) {
        var upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.endsWith(".SF")
                || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC"));
    }
}
