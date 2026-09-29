package cc.sirrus.ysmlib.audio;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface AudioProvider {
    String id();
    /** Validates admission metadata, retains an owning copy, never advances input. */
    PcmStream open(ByteBuffer encoded, SupportedAudioProbe.MediaInfo expected) throws IOException;
}
