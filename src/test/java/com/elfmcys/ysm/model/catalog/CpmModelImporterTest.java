package com.elfmcys.ysm.model.catalog;

import org.junit.jupiter.api.Test;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.model.catalog.source.CatalogModelLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CpmModelImporterTest {
    @Test
    void nullModelHashDoesNotEnterConcurrentMap() {
        assertTrue(CpmModelImporter.createPoseSession(null).isEmpty());
    }

    @Test
    void importsLegacyDirectoryIntoPlayerContainer() throws Exception {
        var root = Files.createTempDirectory("cpm-import-");
        try {
            Files.writeString(root.resolve("model.json"), """
                    {"modelId":"smoke","modelName":"Smoke","bones":[
                      {"id":"hat","parent":"none","boxes":[]}
                    ]}
                    """);
            var captured = new CpmModelImporter().capture(root);
            var output = Files.createTempDirectory("cpm-output-");
            var compiled = new CpmModelImporter().convert(captured, output);
            assertTrue(Files.isRegularFile(compiled.stagedContainer()));
            try (var verified = com.elfmcys.ysm.model.storage.ManagedContainer.verifyFile(
                    compiled.stagedContainer())) {
                assertEquals(compiled.modelHash(), verified.identity().modelId());
            }
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void importsRemakeGeometryAndPersistsSourceClosure() throws Exception {
        var root = Files.createTempDirectory("cpm-remake-");
        try {
            Files.writeString(root.resolve("model.json"), """
                    {"name":"Remake","skeleton":{"type":"biped"},
                     "parts":[{"name":"body","texture":"skin"}]}
                    """);
            Files.writeString(root.resolve("body.json"), """
                    {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"body","texture_width":64,"texture_height":64},"bones":[{"name":"body","pivot":[0,0,0],"cubes":[{"origin":[-2,0,-1],"size":[4,8,2],"uv":[0,0]}]}]}]}
                    """);
            Files.writeString(root.resolve("animation.js"), "function tick(e,m){}\n");
            var captured = new CpmModelImporter().capture(root);
            var output = Files.createTempDirectory("cpm-remake-output-");
            var compiled = new CpmModelImporter().convert(captured, output);
            var verified = com.elfmcys.ysm.model.storage.ManagedContainer.openDirect(compiled.stagedContainer(),
                    new CatalogModelLocation(CatalogRootKind.CUSTOM, new ModelPath("cpm")));
            try {
                var chunk = verified.view().getFileView().getAssetView().getChunkInfo(ModelFileConstant.CPM_SOURCE_CHUNK_NAME);
                assertNotNull(chunk);
                try (var source = verified.chunks().readPayload(chunk, BufferType.ARRAY)) {
                    var bytes = new byte[source.size()]; source.nio().get(bytes);
                    CpmModelImporter.restoreSource(compiled.modelHash(), bytes);
                }
            } finally {
                verified.representation().close();
            }
            assertTrue(CpmModelImporter.createPoseSession(compiled.modelHash()).isPresent());
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }
}
