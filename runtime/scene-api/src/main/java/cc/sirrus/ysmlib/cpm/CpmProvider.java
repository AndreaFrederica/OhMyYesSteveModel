package cc.sirrus.ysmlib.cpm;

import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.io.IOException;
import java.util.*;

/** Portable CPM source parsing. Caller freezes directory/ZIP entries; this service never opens host paths. */
public interface CpmProvider {
    String profile();
    Optional<CpmDocument.Format> detect(ByteData mainJson,ReadLimits limits) throws IOException;
    CpmDocument read(Map<String,ByteData> files,ReadLimits limits) throws IOException;
    CpmGeometry readGeometry(ByteData source,ReadLimits limits) throws IOException;
    /** Compile source into the static common IR; does not evaluate scripts, animation or physics. */
    CpmCompiledModel compile(CpmDocument document);
    /**
     * Compile source into the canonical player form used by YSM adapters.
     * Providers that do not have a player skeleton may return {@link #compile(CpmDocument)}.
     */
    default CpmCompiledModel compileCanonical(CpmDocument document) { return compile(document); }
    /** Creates isolated per-entity dynamic state when the provider supplies it. */
    default Optional<CpmPoseSession> createPoseSession(CpmDocument document) { return Optional.empty(); }
}
