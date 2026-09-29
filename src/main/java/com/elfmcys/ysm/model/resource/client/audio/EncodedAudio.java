package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.client.sound.stream.CustomAudioStream;
import com.elfmcys.ysm.client.sound.stream.OpusAudioStream;
import com.elfmcys.ysm.client.sound.stream.VorbisAudioStream;
import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.UnsupportedAudioFileException;

final class EncodedAudio implements CachedAudio {
    private final UniBuffer encoded;
    private final SupportedAudioProbe.MediaInfo media;
    private final AtomicBoolean closed = new AtomicBoolean();

    EncodedAudio(UniBuffer encoded, SupportedAudioProbe.MediaInfo media) {
        this.encoded = Objects.requireNonNull(encoded, "encoded");
        this.media = Objects.requireNonNull(media, "media");
    }

    CustomAudioStream openDecoder() throws IOException {
        if (closed.get()) {
            throw new IOException("Encoded audio is closed");
        }
        var bytes = encoded.nio().asReadOnlyBuffer();
        try {
            return switch (media.encoding()) {
                case OGG_OPUS -> new OpusAudioStream(bytes, media);
                case OGG_VORBIS -> new VorbisAudioStream(bytes, media);
            };
        } catch (UnsupportedAudioFileException failure) {
            throw new IOException("Admitted audio is not supported by the host adapter", failure);
        }
    }

    SupportedAudioProbe.MediaInfo media() {
        return media;
    }

    @Override
    public int size() {
        return encoded.size();
    }

    @Override
    public boolean pcm() {
        return false;
    }

    @Override
    public EncodedAudio acquire() {
        if (closed.get()) {
            throw new IllegalStateException("Encoded audio is closed");
        }
        return new EncodedAudio(encoded.acquire(), media);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            encoded.close();
        }
    }
}
