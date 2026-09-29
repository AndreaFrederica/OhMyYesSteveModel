package cc.sirrus.ysmlib.audio;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Session-owned streaming decoder. Output is signed mono PCM16, little endian. */
public interface PcmStream extends AutoCloseable {
    /** Writes complete frames, advances destination, returns written bytes or zero at EOF. */
    int read(ByteBuffer destination) throws IOException;
    @Override void close();
}
