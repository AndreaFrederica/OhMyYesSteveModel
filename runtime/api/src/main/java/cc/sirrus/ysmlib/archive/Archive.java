package cc.sirrus.ysmlib.archive;

import java.io.IOException;
import java.util.List;

/** Portable names use '/' and are case-sensitive. Instances are caller-confined. */
public interface Archive extends AutoCloseable {
    /** Immediate child names, sorted; null/empty directory denotes the root. */
    List<String> files(String directory);
    List<String> directories(String directory);
    boolean contains(String path);
    /** Fresh owning bytes, or null for a missing file. */
    byte[] read(String path) throws IOException;
    @Override void close() throws IOException;
}
