package com.elfmcys.ysm.mock.supervisor;

import com.elfmcys.ysm.mock.evidence.EvidenceJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Binds a run to the actual prerequisite JAR, including uncommitted implementation changes. */
record RuntimeCandidateIdentity(String revision, String runtimeLibrarySha256,
                                String manifestPath, String manifestSha256) {
    static RuntimeCandidateIdentity capture(Path evidence, Path library, String revision) throws IOException {
        String hash = EvidenceJson.sha256(Files.readAllBytes(library));
        byte[] manifest = EvidenceJson.canonicalBytes(Map.of("schema", "YSM-JVM-CANDIDATE-1",
                "revision", revision, "runtimeLibrarySha256", hash,
                "javaOnly", true, "path", library.toString()));
        Path path = evidence.resolve("runtime-candidate.json");
        Files.write(path, manifest);
        return new RuntimeCandidateIdentity(revision, hash, path.toString(), EvidenceJson.sha256(manifest));
    }

    void verifyRuntimeLibrary(Path library) throws IOException {
        if (!runtimeLibrarySha256.equals(EvidenceJson.sha256(Files.readAllBytes(library))))
            throw new IOException("Runtime prerequisite changed during verification");
    }
}
