package com.elfmcys.ysm.model.catalog.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModelSourceDiscoveryTest {
    @TempDir
    Path temp;

    @Test
    void discoversModernLegacyAndArchiveSourcesWithoutDescendingIntoModels() throws Exception {
        var modern = Files.createDirectories(temp.resolve("category/modern"));
        Files.writeString(modern.resolve("ysm.json"), "{}");
        Files.createDirectories(modern.resolve("models"));
        Files.writeString(modern.resolve("models/main.json"), "not another model");

        var legacy = Files.createDirectories(temp.resolve("legacy"));
        Files.writeString(legacy.resolve("main.json"), "{}");
        Files.writeString(temp.resolve("packed.ysm"), "archive");

        var result = ModelSourceDiscovery.discover(temp);

        assertEquals(List.of("category/modern", "legacy", "packed.ysm"),
                result.models().stream().map(this::relative).toList());
        assertFalse(result.packDirectories().stream()
                .map(this::relative).anyMatch(path -> path.startsWith("category/modern/")));
        assertEquals(List.of(), result.failures());
    }

    @Test
    void classifiesMxcAndHistoricalYsmHeadersWithoutGuessing() throws Exception {
        Files.writeString(temp.resolve("current.mxc"), "validated when opened");
        Files.write(temp.resolve("v1.ysm"), rawHeader(1));
        Files.write(temp.resolve("v2.ysm"), rawHeader(2));
        Files.write(temp.resolve("v3.ysm"), v3Header());
        Files.writeString(temp.resolve("unknown.ysm"), "unknown header");

        var state = assertInstanceOf(RootInventoryState.Complete.class,
                ModelSourceDiscovery.inventory(new ModelCatalogSource(
                        CatalogRootKind.CUSTOM, temp, false)));
        Map<String, ModelSourceKind> kinds = state.sources().values().stream()
                .collect(Collectors.toMap(
                        value -> value.key().relativePath().value(),
                        value -> value.key().sourceKind()));

        assertEquals(ModelSourceKind.DIRECT_CONTAINER, kinds.get("current.mxc"));
        assertEquals(ModelSourceKind.CURRENT_RAW_ARCHIVE, kinds.get("v1.ysm"));
        assertEquals(ModelSourceKind.CURRENT_RAW_ARCHIVE, kinds.get("v2.ysm"));
        assertEquals(ModelSourceKind.LEGACY_ARCHIVE, kinds.get("v3.ysm"));
        assertEquals(ModelSourceKind.UNSUPPORTED_YSM, kinds.get("unknown.ysm"));
    }

    @Test
    void sidecarEditObservesOnlyItsModelWithoutWalkingSiblingTrees() throws Exception {
        var model = Files.writeString(temp.resolve("avatar.pmx"), "model");
        var variant = Files.writeString(temp.resolve("variant.pmx"), "variant");
        var sibling = Files.createDirectories(temp.resolve("unrelated/nested"));
        var other = Files.writeString(sibling.resolve("other.mxc"), "before");
        var root = new ModelCatalogSource(CatalogRootKind.CUSTOM, temp, false);
        var before = assertInstanceOf(RootInventoryState.Complete.class, ModelSourceDiscovery.inventory(root));
        var profile = Files.writeString(temp.resolve("avatar.pmx.omysm.json"), "{}");
        Files.writeString(other, "unobserved new content");
        Files.writeString(sibling.resolve("unobserved.mxc"), "not part of this event");

        var after = assertInstanceOf(RootInventoryState.Complete.class,
                ModelSourceDiscovery.inventoryChanged(root, before, Set.of(profile)));

        assertEquals(3, after.sources().size());
        assertNotEquals(observation(before, model), observation(after, model));
        assertSame(observation(before, variant), observation(after, variant));
        assertSame(observation(before, other), observation(after, other));
    }

    @Test
    void dependencyEventsRefreshOwnersAndPreserveUnrelatedModels() throws Exception {
        var directory = Files.createDirectories(temp.resolve("avatar/textures"));
        var model = Files.writeString(directory.getParent().resolve("avatar.pmx"), "model");
        var texture = Files.writeString(directory.resolve("color.png"), "before");
        var other = Files.writeString(temp.resolve("other.mxc"), "other");
        var root = new ModelCatalogSource(CatalogRootKind.CUSTOM, temp, false);
        var before = assertInstanceOf(RootInventoryState.Complete.class, ModelSourceDiscovery.inventory(root));
        Files.writeString(texture, "changed pixels");

        var after = assertInstanceOf(RootInventoryState.Complete.class,
                ModelSourceDiscovery.inventoryChanged(root, before, Set.of(texture)));

        assertNotEquals(observation(before, model).stamp(), observation(after, model).stamp());
        assertSame(observation(before, other), observation(after, other));
        var motion = Files.writeString(directory.getParent().resolve("dance.vmd"), "motion");
        var withMotion = assertInstanceOf(RootInventoryState.Complete.class,
                ModelSourceDiscovery.inventoryChanged(root, after, Set.of(motion)));
        assertNotEquals(observation(after, model).stamp(), observation(withMotion, model).stamp());
        Files.delete(motion);
        assertEquals(after, ModelSourceDiscovery.inventoryChanged(root, withMotion, Set.of(motion)));
    }

    @Test
    void exactDeletionDoesNotDiscoverSiblingAdditionsAndPackEditDoesNotTouchModels() throws Exception {
        var first = Files.writeString(temp.resolve("first.mxc"), "first");
        var second = Files.writeString(temp.resolve("second.mxc"), "second");
        var pack = Files.writeString(temp.resolve("ysm-pack.json"), "{}");
        var root = new ModelCatalogSource(CatalogRootKind.CUSTOM, temp, false);
        var before = assertInstanceOf(RootInventoryState.Complete.class, ModelSourceDiscovery.inventory(root));
        Files.delete(first);
        Files.writeString(temp.resolve("third.mxc"), "not included in deletion event");
        Files.writeString(pack, "{\"name\":\"changed\"}");
        var after = assertInstanceOf(RootInventoryState.Complete.class,
                ModelSourceDiscovery.inventoryChanged(root, before, Set.of(first, pack)));
        assertEquals(1, after.sources().size());
        assertSame(observation(before, second), observation(after, second));
        assertNotEquals(before.packs(), after.packs());
    }

    @Test
    void rawModelAndDirectoryDeletionStayWithinTheirScope() throws Exception {
        var raw = Files.createDirectories(temp.resolve("raw"));
        Files.writeString(raw.resolve("ysm.json"), "{}");
        var geometry = Files.writeString(raw.resolve("geometry.json"), "before");
        var other = Files.writeString(temp.resolve("other.mxc"), "other");
        var root = new ModelCatalogSource(CatalogRootKind.CUSTOM, temp, false);
        var before = assertInstanceOf(RootInventoryState.Complete.class, ModelSourceDiscovery.inventory(root));
        Files.writeString(geometry, "changed geometry");
        var changed = assertInstanceOf(RootInventoryState.Complete.class,
                ModelSourceDiscovery.inventoryChanged(root, before, Set.of(geometry)));
        assertNotEquals(observation(before, raw), observation(changed, raw));
        assertSame(observation(before, other), observation(changed, other));
        Files.delete(geometry);
        Files.delete(raw.resolve("ysm.json"));
        Files.delete(raw);
        var deleted = assertInstanceOf(RootInventoryState.Complete.class,
                ModelSourceDiscovery.inventoryChanged(root, changed, Set.of(raw)));
        assertEquals(1, deleted.sources().size());
    }

    private SourceObservation observation(RootInventoryState.Complete inventory, Path path) {
        return inventory.sources().values().stream()
                .filter(source -> source.absolutePath().equals(path.toAbsolutePath().normalize()))
                .findFirst().orElseThrow();
    }

    private static byte[] rawHeader(int version) {
        return ByteBuffer.allocate(8).put(new byte[]{'Y', 'S', 'G', 'P'})
                .putInt(version).array();
    }

    private static byte[] v3Header() {
        return ByteBuffer.allocate(7 + 1 + Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF,
                        'Y', 'S', 'G', 'P'})
                .put((byte) 0)
                .putInt(3)
                .array();
    }

    private String relative(Path path) {
        return temp.relativize(path).toString().replace('\\', '/');
    }
}
