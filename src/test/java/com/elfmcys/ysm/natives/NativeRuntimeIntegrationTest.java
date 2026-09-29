package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@ExtendWith(NativeLibraryExtension.class)
class NativeRuntimeIntegrationTest {
    @Test
    void initializesIdempotentlyWithTheSameJavaConfig() {
        assertDoesNotThrow(() -> NativeRuntime.initialize(
                NativeRuntime.JavaConfig.fromLog4j(Level.INFO)));
    }

    @Test
    void opensProfilerZones() {
        assertDoesNotThrow(() -> {
            try (var ignored = NativeProfiler.beginAnimatableUpdate()) {
            }
            try (var ignored = NativeProfiler.beginFallbackVertexWrite()) {
            }
            try (var ignored = NativeProfiler.beginAnimatableUpdate()) {
            }
        });
    }
}
