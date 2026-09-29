package com.elfmcys.ysm.command.sub;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Command paths are local to explicit roots, never arbitrary server filesystem paths. */
final class V3dCommandPaths {
    private V3dCommandPaths() {}

    static Path existing(Path root, String relative, boolean directory) throws IOException {
        if (relative == null || relative.isBlank() || relative.indexOf(':') >= 0)
            throw new IOException("Expected a relative path inside the model/workspace directory");
        String[] parts = relative.replace('\\', '/').split("/", -1);
        Path path = root.toAbsolutePath().normalize();
        for (String part : parts) {
            if (part.isEmpty() || part.endsWith(".") || part.endsWith(" "))
                throw new IOException("Path traversal is not allowed");
            path = path.resolve(part);
        }
        rejectLinks(path);
        boolean valid = directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if (!valid) throw new IOException("Source does not exist: " + relative);
        if (!path.toRealPath().startsWith(root.toRealPath()))
            throw new IOException("Source resolves outside the permitted directory");
        return path;
    }

    static Path outputDirectory(Path root) throws IOException {
        Path path = root.toAbsolutePath().normalize();
        rejectLinks(path);
        Files.createDirectories(path);
        return path;
    }

    private static void rejectLinks(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Symbolic links are not allowed in command paths");
        }
    }
}
