package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.physics.PhysicsProvider;
import cc.sirrus.ysmlib.scene.physics.natives.NativePhysicsProvider;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import java.util.function.Supplier;

/** Select once at startup. A running world's state never moves between solvers. */
final class PhysicsServices {
    final PhysicsProvider provider;
    final String fallbackReason;

    private PhysicsServices(PhysicsProvider provider, String fallbackReason) {
        this.provider = provider;
        this.fallbackReason = fallbackReason;
    }

    static PhysicsServices configured() {
        return create(Boolean.getBoolean("ysm.runtime.javaOnly"), () -> {
            var library = NativeLibraries.find("physics");
            return library == null ? null : new NativePhysicsProvider(library);
        });
    }

    static PhysicsServices create(boolean javaOnly, Supplier<? extends PhysicsProvider> loader) {
        if (javaOnly) return baseline("javaOnly");
        try {
            var nativeProvider = loader.get();
            return nativeProvider == null ? baseline("native library not installed")
                    : new PhysicsServices(nativeProvider, "");
        } catch (LinkageError | SecurityException | IllegalArgumentException | IllegalStateException unavailable) {
            System.getLogger(PhysicsServices.class.getName()).log(System.Logger.Level.WARNING,
                    "Optional physics accelerator unavailable; using JVM/WASM", unavailable);
            String message = unavailable.getMessage();
            String reason = unavailable.getClass().getSimpleName()
                    + (message == null ? "" : ": " + message.replace('\n', ' ').replace('\r', ' '));
            return baseline(reason.length() <= 180 ? reason : reason.substring(0, 180));
        }
    }

    private static PhysicsServices baseline(String reason) {
        return new PhysicsServices(new WasmPhysicsProvider(), reason);
    }
}
