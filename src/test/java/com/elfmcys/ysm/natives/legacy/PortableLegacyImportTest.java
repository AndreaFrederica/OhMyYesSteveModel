package com.elfmcys.ysm.natives.legacy;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.legacy.java.JavaLegacyImportProvider;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelData;
import com.elfmcys.ysm.proto.mixel.manifest.Manifest;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PortableLegacyImportTest {
    @TempDir Path temporary;

    @Test void importsConfiguredModelsThroughProductionRouting() throws Exception {
        String configured = System.getenv("YSM_MIGRATION_MODELS");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null);
        var raw = new com.elfmcys.ysm.model.catalog.RawModelImporter(
                com.elfmcys.ysm.format.parser.DefaultAnimationFilter.keepAll());
        try (var paths = Files.list(Path.of(configured))) {
            for (var path : paths.filter(p -> p.toString().endsWith(".ysm")).toList()) {
                try {
                    var version = com.elfmcys.ysm.format.legacy.LegacyYsmHeader.probe(path);
                    if (version == com.elfmcys.ysm.format.legacy.LegacyYsmHeader.Version.V3_ENCRYPTED) {
                        new LegacyModelImporter().stage(path, temporary);
                        var workspace = new com.elfmcys.ysm.format.legacy.V3dCache()
                                .materialize(path, temporary.resolve("decoded"));
                        com.elfmcys.ysm.format.legacy.V3dCache.validate(workspace);
                        var restored = temporary.resolve(path.getFileName() + ".restored");
                        com.elfmcys.ysm.format.legacy.V3dCache.restoreOriginal(workspace, restored);
                        assertEquals(-1, Files.mismatch(path, restored));
                    } else {
                        try (var captured = raw.capture(path)) { raw.convert(captured, temporary); }
                    }
                } catch (Exception failure) { throw new AssertionError("Import failed: " + path, failure); }
            }
        }
    }

    @Test void stagesEveryHistoricalVersionAgainstGeneratedSchema() throws Exception {
        var provider = new JavaLegacyImportProvider(YsmRuntime.v3(), YsmRuntime.images());
        for (int version = 1; version <= 32; version++) {
            byte[] wire = Files.readAllBytes(Path.of("runtime/java-v3/src/test/resources/historical/v" + version + ".wire"));
            var bundle = provider.projectWire(wire, wire.length);
            byte[] manifestBytes = new byte[bundle.payloads().get(0).bytes().remaining()];
            bundle.payloads().get(0).bytes().get(manifestBytes);
            var manifest = Manifest.parseFrom(manifestBytes);
            assertEquals("player", manifest.renderTargets().get(0).targetId());
            byte[] modelBytes = new byte[bundle.payloads().get(2).bytes().remaining()];
            bundle.payloads().get(2).bytes().get(modelBytes);
            assertNotNull(ModelData.parseFrom(modelBytes));
            var staged = LegacyModelImporter.stageBundle(bundle, temporary.resolve("v" + version));
            try (var channel = FileChannel.open(staged.stagedContainer())) {
                assertEquals(staged.modelHash(), new ModelFileView(channel).getModelHash());
            }
        }
    }
}
