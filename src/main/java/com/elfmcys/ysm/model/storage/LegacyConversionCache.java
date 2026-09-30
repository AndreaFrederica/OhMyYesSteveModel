package com.elfmcys.ysm.model.storage;

import cc.sirrus.ysmlib.v3d.V3dCache;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.source.CatalogModelLocation;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Source-fingerprinted conversion receipts; the ordinary converted store still owns objects. */
public final class LegacyConversionCache {
    private static final int MAGIC = 0x594c4331; // YLC1
    private final Path root;
    private final ConvertedObjectStore objects;
    private final AtomicSharedCache cache;
    private final String profile;

    LegacyConversionCache(Path cacheRoot, ConvertedObjectStore objects, AtomicSharedCache cache,
                          String modVersion, String importerProfile) {
        this.root = cacheRoot.resolve("legacy");
        this.objects = objects;
        this.cache = cache;
        this.profile = "receipt-1\0" + modVersion + "\0" + importerProfile;
    }

    public Path wireRoot() { return root.resolve("v3d"); }

    public <T> T withSourceLock(V3dCache.CapturedSource source,
                               AtomicSharedCache.LockedOperation<T> action) throws IOException {
        // All versions/projectors sharing one wire generation serialize publication together.
        return cache.withKeyLock("legacy-source", source.sha256(), action);
    }

    public Optional<ManagedContainer> find(V3dCache.CapturedSource source,
                                           CatalogModelLocation location) throws IOException {
        var key = key(source);
        var receipt = receiptPath(key);
        if (!RegularFileProbe.exists(receipt)) return Optional.empty();
        final ModelFileIdentity identity;
        final String fingerprint;
        try (var input = new DataInputStream(Files.newInputStream(receipt))) {
            if (Files.size(receipt) > 1024 || input.readInt() != MAGIC
                    || !input.readUTF().equals(key)) return Optional.empty();
            identity = new ModelFileIdentity(hash(input.readUTF()), hash(input.readUTF()));
            fingerprint = input.readUTF();
            hash(fingerprint);
            if (input.read() != -1) return Optional.empty();
        } catch (EOFException | UTFDataFormatException | IllegalArgumentException malformed) {
            return Optional.empty();
        }
        var file = objects.objectPath(identity.modelId(), identity.containerId());
        if (!RegularFileProbe.exists(file)) return Optional.empty();
        try {
            return Optional.of(ManagedContainer.openFingerprinted(
                    new CatalogIndexEntry(identity, location, file), fingerprint).content());
        } catch (AssetLoadException failure) {
            if (failure.reason() == AssetLoadException.Reason.ACCESS) throw failure;
            return Optional.empty();
        } catch (FileSystemException failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /** Called only after commit. Validates every payload before recording its stored-byte hash. */
    public ManagedContainer record(V3dCache.CapturedSource source, CatalogIndexEntry entry)
            throws IOException {
        var verified = ManagedContainer.openFingerprinted(entry, null);
        var target = receiptPath(key(source));
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
            try (var output = new DataOutputStream(Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                output.writeInt(MAGIC);
                output.writeUTF(key(source));
                output.writeUTF(entry.modelId().toString());
                output.writeUTF(entry.identity().containerId().toString());
                output.writeUTF(verified.sha256());
            }
            AtomicSharedCache.moveCommitted(temporary, target);
            return verified.content();
        } catch (IOException | RuntimeException | Error failure) {
            verified.content().representation().close();
            throw failure;
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
    }

    private String key(V3dCache.CapturedSource source) {
        return HexFormat.of().formatHex(ManagedContainer.sha256().digest(
                (profile + "\0" + source.sha256()).getBytes(StandardCharsets.UTF_8)));
    }

    private Path receiptPath(String key) {
        return cache.checkedTarget(root.resolve("converted").resolve(key + ".receipt"));
    }

    private static Hash256 hash(String value) {
        if (!value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid receipt hash");
        return new Hash256(HexFormat.of().parseHex(value));
    }
}
