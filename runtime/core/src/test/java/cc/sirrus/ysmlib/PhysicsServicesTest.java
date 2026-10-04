package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhysicsServicesTest {
    @Test void javaOnlyNeverInvokesDiscoveryOrLoading() {
        var services = PhysicsServices.create(true, () -> { throw new AssertionError("Loader invoked"); });
        assertInstanceOf(WasmPhysicsProvider.class, services.provider);
        assertEquals("javaOnly", services.fallbackReason);
    }

    @Test void missingLibraryKeepsManagedSolver() {
        var services = PhysicsServices.create(false, () -> null);
        assertInstanceOf(WasmPhysicsProvider.class, services.provider);
        assertEquals("native library not installed", services.fallbackReason);
    }

    @Test void successfulSelectionIsStableAndDiagnosticsDoNotLoadAgain() {
        var accepted = new WasmPhysicsProvider();
        int[] loads = {0};
        var services = PhysicsServices.create(false, () -> { loads[0]++; return accepted; });
        assertSame(accepted, services.provider);
        for (int i = 0; i < 10; i++) assertEquals("", services.fallbackReason);
        assertEquals(1, loads[0]);
    }

    @Test void linkageAbiAndSelfCheckFailuresKeepManagedSolver() {
        for (var message : new String[]{"missing JNI symbol", "ABI/precision/capability mismatch", "trajectory self-check failed"}) {
            var services = PhysicsServices.create(false, () -> { throw new UnsatisfiedLinkError(message); });
            assertInstanceOf(WasmPhysicsProvider.class, services.provider);
            assertTrue(services.fallbackReason.contains(message));
        }
    }

    @Test void startupPolicyAndWorldRejectionsKeepManagedSolver() {
        for (RuntimeException failure : new RuntimeException[]{new SecurityException("blocked"),
                new java.nio.file.InvalidPathException("bad", "path"),
                new IllegalStateException("Native world creation failed"),
                new IllegalArgumentException("Native physics input rejected")}) {
            var services = PhysicsServices.create(false, () -> { throw failure; });
            assertInstanceOf(WasmPhysicsProvider.class, services.provider);
            assertTrue(services.fallbackReason.startsWith(failure.getClass().getSimpleName()));
        }
    }

    @Test void unrelatedProgrammingErrorsAreNotHiddenByFallback() {
        assertThrows(NullPointerException.class, () -> PhysicsServices.create(false, () -> { throw new NullPointerException(); }));
    }
}
