package com.elfmcys.ysm.model.resource.client.audio;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.Objects;

final class PcmAudio implements CachedAudio {
    private final byte[] bytes;
    private final AudioFormat format;

    PcmAudio(byte[] bytes, AudioFormat format) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
        this.format = Objects.requireNonNull(format, "format");
    }

    AudioFormat format() {
        return format;
    }

    ByteBuffer read(int offset, int length) {
        return ByteBuffer.wrap(bytes, offset, length).slice().asReadOnlyBuffer();
    }

    @Override
    public int size() {
        return bytes.length;
    }

    @Override
    public boolean pcm() {
        return true;
    }

    @Override
    public PcmAudio acquire() {
        return this;
    }

    @Override
    public void close() {
        // Immutable heap storage is shared by the cache and active playbacks;
        // each playback copies its reads into an independent host upload buffer.
    }
}
