package com.elfmcys.ysm.tool;

import com.elfmcys.ysm.format.parser.ModelParser;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelMaterializer;
import com.elfmcys.ysm.model.catalog.content.DefaultAnimationKey;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.source.CatalogModelLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelHashing;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BuiltinModelIndexTool {
    private static final ModelPath DEFAULT_PATH = new ModelPath("default");
    private static final int VERIFICATION_RECEIPT_FORMAT_VERSION = 1;

    private BuiltinModelIndexTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw usage();
        }
        var expectedArguments = switch (args[0]) {
            case "generate-default-contract" -> 4;
            case "generate-index", "verify" -> 6;
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        };
        if (args.length != expectedArguments) {
            throw usage();
        }

        var sourceRoot = Path.of(args[1]).toAbsolutePath().normalize();
        var resultFile = Path.of(args[2]).toAbsolutePath().normalize();
        var workDirectory = Path.of(args[3]).toAbsolutePath().normalize();
        switch (args[0]) {
            case "generate-default-contract" ->
                    generateDefaultContract(sourceRoot, resultFile, workDirectory);
            case "generate-index" -> generateIndex(sourceRoot, resultFile,
                    Path.of(args[4]).toAbsolutePath().normalize(),
                    Path.of(args[5]).toAbsolutePath().normalize());
            case "verify" -> verify(sourceRoot, resultFile, workDirectory,
                    Path.of(args[4]).toAbsolutePath().normalize(),
                    Path.of(args[5]).toAbsolutePath().normalize());
            default -> throw new AssertionError("Command was validated before native load");
        }
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("Usage:\n"
                + "  generate-default-contract <default-root> <contract-file> <work-dir>\n"
                + "  generate-index <builtin-root> <index-file> <work-dir> "
                + "<default-contract> <history-file>\n"
                + "  verify <builtin-root> <receipt-file> <work-dir> "
                + "<index-file> <default-contract>");
    }

    private static void generateDefaultContract(Path defaultRoot, Path contractFile,
                                                Path workDirectory) throws IOException {
        Files.deleteIfExists(contractFile);
        if (!Files.isDirectory(defaultRoot)) {
            throw new IOException("Builtin default model root is unavailable: " + defaultRoot);
        }
        Files.createDirectories(workDirectory);

        final Hash256 scanned;
        try (var vfs = new Directory(defaultRoot)) {
            scanned = ModelParser.scanModelHash(vfs);
        }
        var outputDirectory = workDirectory.resolve("converted");
        Files.createDirectories(outputDirectory);
        final Path converted;
        try (var vfs = new Directory(defaultRoot)) {
            converted = ModelParser.parseBuiltinDefault(vfs, outputDirectory);
        }
        var location = new CatalogModelLocation(CatalogRootKind.BUILTIN, DEFAULT_PATH);
        var handle = openIndexed(converted, location);
        if (!scanned.equals(handle.representation().modelId())) {
            throw new IOException("Builtin default full-conversion hash mismatch: dryRun="
                    + scanned + ", converted=" + handle.representation().modelId());
        }
        var materialized = BuiltinModelMaterializer.materialize(handle);
        if (!scanned.equals(materialized.modelHash())) {
            throw new IOException("Builtin default materialization hash mismatch: dryRun="
                    + scanned + ", materialized=" + materialized.modelHash());
        }

        var contract = createDefaultContract(scanned, materialized.animationHashes());
        writeIndexAtomically(contract, contractFile);
        System.out.printf("Generated builtin default contract: modelHash=%s defaultAnimations=%d output=%s%n",
                scanned, contract.animationEntries().size(), contractFile);
    }

    private static void generateIndex(Path builtinRoot, Path indexFile,
                                      Path defaultContractFile, Path historyFile)
            throws IOException {
        Files.deleteIfExists(indexFile);
        var discovery = discoverRawDirectories(builtinRoot);
        var hashes = new LinkedHashMap<ModelPath, Hash256>();
        for (var source : discovery.models()) {
            var path = ModelPath.relativeTo(builtinRoot, source);
            final Hash256 scanned;
            try (var vfs = new Directory(source)) {
                scanned = ModelParser.scanModelHash(vfs);
            }
            hashes.put(path, scanned);
        }

        var defaultContract = BuiltinModelIndex.read(defaultContractFile);
        var history = BuiltinModelIndex.readAnimationHistory(historyFile);
        var index = createIndex(hashes, defaultContract, history);
        writeIndexAtomically(index, indexFile);
        System.out.printf("Generated builtin model index from content hashes: models=%d "
                        + "defaultAnimations=%d output=%s%n",
                index.entries().size(), index.animationEntries().size(), indexFile);
    }

    private static void verify(Path builtinRoot, Path receiptFile, Path workDirectory,
                               Path indexFile, Path defaultContractFile) throws IOException {
        Files.deleteIfExists(receiptFile);
        var index = BuiltinModelIndex.read(indexFile);
        var defaultContract = BuiltinModelIndex.read(defaultContractFile);
        validateDefaultContract(index, defaultContract);

        var discovery = discoverRawDirectories(builtinRoot);
        var discoveredPaths = discovery.models().stream()
                .map(source -> ModelPath.relativeTo(builtinRoot, source)).toList();
        index.validateCoverage(discoveredPaths);
        Files.createDirectories(workDirectory);

        var materializedNonDefault = 0;
        for (var source : discovery.models()) {
            var path = ModelPath.relativeTo(builtinRoot, source);
            if (path.equals(DEFAULT_PATH)) {
                continue;
            }
            var expected = index.require(path);
            var outputDirectory = workDirectory.resolve(path.value());
            Files.createDirectories(outputDirectory);
            final Path converted;
            try (var vfs = new Directory(source)) {
                converted = ModelParser.parse(vfs, outputDirectory, index);
            }
            var location = new CatalogModelLocation(CatalogRootKind.BUILTIN, path);
            var handle = openIndexed(converted, location);
            var actual = handle.representation().modelId();
            if (!expected.equals(actual)) {
                throw new IOException("Builtin full-conversion hash mismatch at " + path
                        + ": index=" + expected + ", converted=" + actual);
            }
            var materialized = BuiltinModelMaterializer.materialize(handle);
            if (!expected.equals(materialized.modelHash())) {
                throw new IOException("Builtin materialization hash mismatch at " + path
                        + ": index=" + expected + ", materialized=" + materialized.modelHash());
            }
            materializedNonDefault++;
        }

        var receipt = verificationReceipt(ModelHashing.blake3(indexFile), index.entries().size());
        writeTextAtomically(receipt, receiptFile);
        System.out.printf("Verified builtin full materialization: defaultProof=1 "
                        + "materializedNonDefault=%d models=%d receipt=%s%n",
                materializedNonDefault, index.entries().size(), receiptFile);
    }

    static BuiltinModelIndex createDefaultContract(
            Hash256 defaultHash, Map<DefaultAnimationKey, Hash256> currentAnimations)
            throws IOException {
        return BuiltinModelIndex.of(Map.of(DEFAULT_PATH, defaultHash), currentAnimations, Map.of());
    }

    private static ManagedContainer openIndexed(Path file, CatalogModelLocation location)
            throws IOException {
        final ModelFileIdentity identity;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            identity = ModelFileIdentityReader.read(channel);
        }
        return ManagedContainer.openIndexed(new CatalogIndexEntry(
                identity, location, file));
    }

    static BuiltinModelIndex createIndex(
            Map<ModelPath, Hash256> modelHashes,
            BuiltinModelIndex defaultContract,
            Map<DefaultAnimationKey, Set<Hash256>> history) throws IOException {
        requireDefaultOnlyContract(defaultContract);
        var scannedDefault = modelHashes.get(DEFAULT_PATH);
        if (scannedDefault == null) {
            throw new IOException("Builtin scan did not discover the default model");
        }
        var contractedDefault = defaultContract.require(DEFAULT_PATH);
        if (!scannedDefault.equals(contractedDefault)) {
            throw new IOException("Builtin default contract hash mismatch: scan=" + scannedDefault
                    + ", contract=" + contractedDefault);
        }
        return BuiltinModelIndex.of(modelHashes, currentAnimationHashes(defaultContract), history);
    }

    static void validateDefaultContract(BuiltinModelIndex index,
                                        BuiltinModelIndex defaultContract) throws IOException {
        requireDefaultOnlyContract(defaultContract);
        var indexedDefault = index.require(DEFAULT_PATH);
        var contractedDefault = defaultContract.require(DEFAULT_PATH);
        if (!indexedDefault.equals(contractedDefault)) {
            throw new IOException("Builtin default proof does not match final index: index="
                    + indexedDefault + ", contract=" + contractedDefault);
        }
        var indexedAnimations = currentAnimationHashes(index);
        var contractedAnimations = currentAnimationHashes(defaultContract);
        if (!indexedAnimations.equals(contractedAnimations)) {
            throw new IOException("Builtin default animation proof does not match final index");
        }
    }

    static String verificationReceipt(Hash256 indexHash, int modelCount) {
        if (modelCount < 1) {
            throw new IllegalArgumentException("Verified builtin model count must be positive");
        }
        return "{\n"
                + "  \"formatVersion\": " + VERIFICATION_RECEIPT_FORMAT_VERSION + ",\n"
                + "  \"builtinIndexHash\": \"" + indexHash + "\",\n"
                + "  \"verifiedModelCount\": " + modelCount + "\n"
                + "}\n";
    }

    private static void requireDefaultOnlyContract(BuiltinModelIndex contract) throws IOException {
        var entries = contract.entries();
        if (entries.size() != 1 || !entries.get(0).path().equals(DEFAULT_PATH)) {
            throw new IOException("Builtin default contract must contain exactly the default model");
        }
        for (var animation : contract.animationEntries()) {
            if (!animation.acceptedPayloadHashes().equals(
                    List.of(animation.currentPayloadHash()))) {
                throw new IOException("Builtin default contract must contain only current animation hashes: "
                        + animation.key());
            }
        }
    }

    private static Map<DefaultAnimationKey, Hash256> currentAnimationHashes(
            BuiltinModelIndex index) {
        var animations = new LinkedHashMap<DefaultAnimationKey, Hash256>();
        index.animationEntries().forEach(entry ->
                animations.put(entry.key(), entry.currentPayloadHash()));
        return Map.copyOf(animations);
    }

    private static ModelSourceDiscovery.Result discoverRawDirectories(Path builtinRoot)
            throws IOException {
        if (!Files.isDirectory(builtinRoot)) {
            throw new IOException("Builtin model root is unavailable: " + builtinRoot);
        }
        var discovery = ModelSourceDiscovery.discover(builtinRoot);
        if (!discovery.failures().isEmpty()) {
            var failure = discovery.failures().get(0);
            throw new IOException("Failed to discover builtin source: " + failure.path(),
                    failure.error());
        }
        for (var source : discovery.models()) {
            if (!Files.isDirectory(source)) {
                throw new IOException("Builtin resources support only raw model directories: "
                        + builtinRoot.relativize(source));
            }
        }
        return discovery;
    }

    private static void writeIndexAtomically(BuiltinModelIndex index, Path output)
            throws IOException {
        writeAtomically(output, index::write);
    }

    private static void writeTextAtomically(String value, Path output) throws IOException {
        writeAtomically(output, temporary -> Files.writeString(
                temporary, value, StandardCharsets.UTF_8));
    }

    private static void writeAtomically(Path output, PathWriter writer) throws IOException {
        output = output.toAbsolutePath().normalize();
        var parent = output.getParent();
        Files.createDirectories(parent);
        var temporary = Files.createTempFile(parent, output.getFileName() + ".", ".tmp");
        var moved = false;
        try {
            writer.write(temporary);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    @FunctionalInterface
    private interface PathWriter {
        void write(Path path) throws IOException;
    }
}
