package com.elfmcys.ysm.mock.classpath;

import com.elfmcys.ysm.natives.NativeRuntime;
import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import org.apache.logging.log4j.Level;

import java.nio.file.Files;
import java.nio.file.Path;

/** Historical harness name; verifies the independent JVM prerequisite without loading JNI. */
public final class MockNativeRuntime {
    private MockNativeRuntime() {
    }

    public static String initialize(Path library) throws Exception {
        library = library.toAbsolutePath().normalize();
        if (!Files.isRegularFile(library)) {
            throw new IllegalArgumentException("Runtime prerequisite does not exist: " + library);
        }
        System.setProperty("ysm.runtime.javaOnly", "true");
        if (!cc.sirrus.ysmlib.YsmRuntime.hashes().id().contains("java")) {
            throw new IllegalStateException("Classpath harness requires the Java hash provider");
        }
        NativeRuntime.initialize(NativeRuntime.JavaConfig.fromLog4j(Level.INFO));
        return EvidenceJson.sha256(Files.readAllBytes(library));
    }
}
