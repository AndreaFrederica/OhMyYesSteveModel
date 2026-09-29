package cc.sirrus.ysmlib.archive.java;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Common path semantics for all Java archive readers. */
final class ArchiveIndex<T> {
    private final Map<String, T> files = new TreeMap<>();
    private final TreeSet<String> directories = new TreeSet<>();

    static String normalize(String path) throws IOException {
        if (path == null) return "";
        // Historical native ArchiveEntry removes one leading '/'.
        if (path.startsWith("/")) path = path.substring(1);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) return "";
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.indexOf('\\') >= 0 || segment.indexOf('\0') >= 0
                    || segment.indexOf(':') >= 0) {
                throw new IOException("Invalid archive path: " + path);
            }
        }
        return path;
    }

    void add(String name, T entry, boolean directory) throws IOException {
        String path = normalize(name);
        if (path.isEmpty()) throw new IOException("Empty archive entry name");
        if (directory) {
            if (files.containsKey(path)) throw new IOException("File/directory collision: " + path);
            directories.add(path);
        } else {
            if (directories.contains(path) || files.putIfAbsent(path, entry) != null) {
                throw new IOException("Duplicate or ambiguous archive entry: " + path);
            }
        }
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            String parent = path.substring(0, slash);
            if (files.containsKey(parent)) throw new IOException("File/directory collision: " + parent);
            directories.add(parent);
        }
    }

    T get(String path) {
        try { return files.get(normalize(path)); }
        catch (IOException invalid) { return null; }
    }

    List<String> list(String directory, boolean folders) {
        final String prefix;
        try {
            String path = normalize(directory);
            prefix = path.isEmpty() ? "" : path + "/";
        } catch (IOException invalid) {
            throw new IllegalArgumentException(invalid.getMessage(), invalid);
        }
        return (folders ? directories : files.keySet()).stream()
                .filter(path -> path.startsWith(prefix))
                .map(path -> path.substring(prefix.length()))
                .filter(path -> path.indexOf('/') < 0).toList();
    }

    void clear() { files.clear(); directories.clear(); }
}
