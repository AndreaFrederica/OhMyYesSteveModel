package cc.sirrus.ysmlib.v3d;

import cc.sirrus.ysmlib.legacy.V3EnvelopeProvider;
import cc.sirrus.ysmlib.legacy.DecodedWorkspaceProvider;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Explicit sidecar operation. Does not participate in catalog or .mxc authority.
 * Callers serialize writes to a cache root and keep source files stable during a call.
 */
public final class V3dCache {
    private static final Gson JSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting().create();
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    public static final int WIRE_BYTE_LIMIT = 256 * 1024 * 1024;
    private final V3EnvelopeProvider decoder;
    private final DecodedWorkspaceProvider workspace;

    /** Capture-only mode, without decoded files. */
    public V3dCache(V3EnvelopeProvider decoder) {
        this(decoder, null);
    }

    /** Providers are injected; the filesystem module has no implementation or game dependency. */
    public V3dCache(V3EnvelopeProvider decoder, DecodedWorkspaceProvider workspace) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.workspace = workspace;
    }

    /** Returns an immutable generation, named by source SHA-256 and decoder profile.
     * Changed inputs get a new generation; existing valid generations are retained.
     */
    public Path materialize(Path source, Path cacheRoot) throws IOException {
        int profile = decoder.profile();
        if (profile < 1) {
            throw new IllegalArgumentException("Decoder profile must be positive");
        }
        long size = Files.size(source);
        if (size < 1 || size > V3EnvelopeProvider.SOURCE_LIMIT) {
            throw new IOException("Legacy source size is outside the supported range");
        }
        ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(size));
        try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
            readExact(channel, bytes);
            if (channel.size() != size) throw new IOException("Source changed during capture");
        }
        bytes.flip();
        String sourceHash = hash(bytes.duplicate());
        Path root = cacheRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (workspace != null && workspace.profile() < 1)
            throw new IllegalArgumentException("Parser profile must be positive");
        Path target = root.resolve(sourceHash + "-p" + profile
                + (workspace == null ? "" : "-d" + workspace.profile()) + ".v3d");
        if (validGeneration(target, sourceHash, size, profile)) return target;
        byte[] plaintext = decoder.decode(bytes.asReadOnlyBuffer(), WIRE_BYTE_LIMIT);
        if (plaintext == null || plaintext.length < 4 || plaintext.length > WIRE_BYTE_LIMIT)
            throw new IOException("Invalid historical wire size");
        ByteBuffer wire = ByteBuffer.wrap(plaintext).order(ByteOrder.LITTLE_ENDIAN);
        int innerVersion = wire.getInt(0);
        if (innerVersion < 1 || innerVersion > 32) throw new IOException("Invalid historical wire version");
        var manifest = new V3dManifest(V3dManifest.FORMAT, V3dManifest.VERSION, profile,
                new V3dManifest.Source(V3dManifest.SOURCE_PATH, sourceHash, size, 3, innerVersion),
                new V3dManifest.Wire(V3dManifest.WIRE_PATH, "none", hash(wire.duplicate()), wire.remaining()));
        Path staging = Files.createTempDirectory(root, ".v3d-pending-");
        try {
            Files.createDirectory(staging.resolve("source"));
            writeBytes(staging.resolve(V3dManifest.SOURCE_PATH), bytes.duplicate());
            writeBytes(staging.resolve(V3dManifest.WIRE_PATH), wire.duplicate());
            if (workspace != null) {
                var files = new java.util.TreeMap<String, V3dManifest.FileDigest>();
                workspace.materialize(wire.asReadOnlyBuffer(), (relative, content) -> {
                    Path path = decodedPath(staging, relative);
                    if (files.containsKey(relative)) throw new IOException("Duplicate decoded path");
                    var descriptor = new V3dManifest.FileDigest(content.remaining(), hash(content.duplicate()));
                    Files.createDirectories(path.getParent());
                    writeBytes(path, content.duplicate());
                    files.put(relative, descriptor);
                });
                manifest = new V3dManifest(manifest.format(), manifest.formatVersion(), profile,
                        manifest.source(), manifest.wire(),
                        new V3dManifest.Decoded(1, workspace.profile(), manifest.wire().sha256(), files));
            }
            Files.writeString(staging.resolve("v3d.json"), JSON.toJson(manifest));
            Files.writeString(staging.resolve("integrity.json"), JSON.toJson(manifest.integrity()));
            validate(staging);
            publish(staging, target);
            return target;
        } finally {
            deleteTree(staging);
        }
    }

    /** Validates and copies the original envelope. Never re-encrypts the wire. */
    public static void restoreOriginal(Path directory, Path output) throws IOException {
        validate(directory, false);
        // The default copy mode refuses to overwrite an existing output.
        Files.copy(directory.resolve(V3dManifest.SOURCE_PATH), output);
    }

    public static V3dManifest validate(Path directory) throws IOException {
        return validate(directory, true);
    }

    private static V3dManifest validate(Path directory, boolean checkDecoded) throws IOException {
        try {
            if (!Files.isDirectory(directory, NO_FOLLOW)
                    || !Files.isDirectory(directory.resolve("source"), NO_FOLLOW)) {
                throw new IOException("V3D directory is missing or is a symbolic link");
            }
            var manifest = readJson(directory.resolve("v3d.json"), V3dManifest.class);
            var integrity = readJson(directory.resolve("integrity.json"),
                    V3dManifest.Integrity.class);
            var source = manifest.source();
            var wire = manifest.wire();
            if (!V3dManifest.FORMAT.equals(manifest.format())
                    || manifest.formatVersion() != V3dManifest.VERSION
                    || manifest.decoderProfile() < 1
                    || !V3dManifest.SOURCE_PATH.equals(source.path())
                    || !V3dManifest.WIRE_PATH.equals(wire.path())
                    || !"none".equals(wire.compression())
                    || source.envelopeVersion() != 3
                    || source.innerVersion() < 1 || source.innerVersion() > 32
                    || source.size() < 1 || source.size() > V3EnvelopeProvider.SOURCE_LIMIT
                    || wire.rawSize() < 4 || wire.rawSize() > WIRE_BYTE_LIMIT
                    || !manifest.integrity().equals(integrity)) {
                throw new IOException("Invalid V3D metadata or dependencies");
            }
            verifyFile(directory.resolve(V3dManifest.SOURCE_PATH), source.size(), source.sha256());
            Path wirePath = directory.resolve(V3dManifest.WIRE_PATH);
            verifyFile(wirePath, wire.rawSize(), wire.sha256());
            var decoded = manifest.decoded();
            if (decoded != null) {
                if (decoded.schemaVersion() != 1 || decoded.parserProfile() < 1
                        || !wire.sha256().equals(decoded.derivedFromWireSha256())
                        || decoded.files() == null || !decoded.files().containsKey("legacy/model.json"))
                    throw new IOException("Invalid decoded workspace dependencies");
                if (checkDecoded) for (var entry : decoded.files().entrySet()) {
                    Path path = decodedPath(directory, entry.getKey());
                    for (Path parent = path.getParent(); !parent.equals(directory.toAbsolutePath().normalize()); parent = parent.getParent())
                        if (!Files.isDirectory(parent, NO_FOLLOW)) throw new IOException("Linked decoded directory");
                    verifyFile(path, entry.getValue().size(), entry.getValue().sha256());
                }
            }
            try (var channel = FileChannel.open(wirePath, StandardOpenOption.READ)) {
                var version = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                readExact(channel, version);
                version.flip();
                if (version.getInt() != source.innerVersion()) {
                    throw new IOException("V3D wire version does not match manifest");
                }
            }
            return manifest;
        } catch (RuntimeException failure) {
            throw new IOException("Invalid V3D metadata", failure);
        }
    }

    private boolean validGeneration(Path path, String hash, long size, int profile) {
        try {
            var manifest = validate(path);
            return manifest.source().sha256().equals(hash)
                    && manifest.source().size() == size && manifest.decoderProfile() == profile
                    && (workspace == null || manifest.decoded() != null
                        && manifest.decoded().parserProfile() == workspace.profile());
        } catch (IOException failure) {
            return false;
        }
    }

    private static <T> T readJson(Path path, Class<T> type) throws IOException {
        if (!Files.isRegularFile(path, NO_FOLLOW) || Files.size(path) > 16 * 1024 * 1024) {
            throw new IOException("Missing or oversized V3D metadata");
        }
        return JSON.fromJson(Files.readString(path), type);
    }

    private static void verifyFile(Path path, long size, String hash) throws IOException {
        if (!Files.isRegularFile(path, NO_FOLLOW) || Files.size(path) != size
                || hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid V3D file: " + path.getFileName());
        }
        var digest = digest();
        try (var input = FileChannel.open(path, StandardOpenOption.READ)) {
            var block = ByteBuffer.allocate(64 * 1024);
            while (input.read(block) >= 0) {
                block.flip();
                digest.update(block);
                block.clear();
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(hash)) {
            throw new IOException("V3D hash mismatch: " + path.getFileName());
        }
    }

    private static Path decodedPath(Path root, String relative) throws IOException {
        if (relative == null || !relative.matches("(?:legacy|geometry|animations|controllers|textures|gui|avatars|sounds|functions|lang)/[A-Za-z0-9_.-]+")
                || relative.contains("..")) throw new IOException("Unsafe decoded path");
        return root.toAbsolutePath().normalize().resolve(relative);
    }

    private static String hash(ByteBuffer bytes) {
        var digest = digest();
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void readExact(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            if (channel.read(bytes) < 0) {
                throw new EOFException("Truncated source");
            }
        }
    }

    private static void writeBytes(Path path, ByteBuffer bytes) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
        }
    }

    static void publish(Path staging, Path target) throws IOException {
        // Valid generations never reach here. Preserve a corrupt/incomplete entry
        // until the replacement rename succeeds; never fall back to recursive copy.
        Path previous = null;
        if (Files.exists(target, NO_FOLLOW)) {
            previous = target.resolveSibling(".v3d-invalid-" + UUID.randomUUID());
            Files.move(target, previous, StandardCopyOption.ATOMIC_MOVE);
        }
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            if (previous != null) {
                try {
                    Files.move(previous, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
            }
            throw failure;
        }
        // Keep quarantined content for inspection; it is never a cache candidate.
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, NO_FOLLOW)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
