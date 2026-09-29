package com.elfmcys.ysm.client.sound.stream;

import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.sound.sampled.UnsupportedAudioFileException;

public final class OpusAudioStream extends RuntimeAudioStream {
    public OpusAudioStream(ByteBuffer data) throws IOException, UnsupportedAudioFileException {
        this(data, inspect(data, SupportedAudioProbe.Encoding.OGG_OPUS));
    }
    public OpusAudioStream(ByteBuffer data, SupportedAudioProbe.MediaInfo media)
            throws IOException, UnsupportedAudioFileException {
        super(data, media, SupportedAudioProbe.Encoding.OGG_OPUS);
    }
}
