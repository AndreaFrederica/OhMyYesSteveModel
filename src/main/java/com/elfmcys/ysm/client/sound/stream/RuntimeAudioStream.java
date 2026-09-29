package com.elfmcys.ysm.client.sound.stream;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.audio.PcmStream;
import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import com.elfmcys.ysm.format.AssetLoadException;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.UnsupportedAudioFileException;

/** Game buffer adapter; all validation and PCM decoding live in the independent prerequisite. */
class RuntimeAudioStream implements CustomAudioStream {
    private static final ByteBuffer EMPTY = ByteBuffer.allocateDirect(0).asReadOnlyBuffer();
    private final PcmStream decoder;
    private final AudioFormat format;
    private ByteBuffer output = ByteBuffer.allocateDirect(8192);
    private boolean eof;
    private volatile boolean closed;

    RuntimeAudioStream(ByteBuffer data, SupportedAudioProbe.MediaInfo media,
                       SupportedAudioProbe.Encoding encoding) throws IOException, UnsupportedAudioFileException {
        if (media.encoding() != encoding) throw new UnsupportedAudioFileException("Unexpected audio encoding");
        float sampleRate = (float) media.sampleRate();
        if ((long) sampleRate != media.sampleRate())
            throw new UnsupportedAudioFileException("Audio sample rate cannot be represented by the host");
        try { decoder = YsmRuntime.audio().open(data, media); }
        catch (IOException failure) { throw AssetLoadException.content("Invalid Ogg audio stream", failure); }
        format = new AudioFormat(sampleRate, 16, 1, true, false);
    }

    @Override public ByteBuffer read(int size) throws IOException {
        if (size < 0) throw new IllegalArgumentException("size must not be negative");
        if (size == 0 || eof || closed) return EMPTY.duplicate();
        int requested = size == 1 ? 2 : size - size % 2;
        if (requested > 256 * 1024 * 1024) throw new IOException("Audio read budget exceeded");
        if (output.capacity() < requested) output = ByteBuffer.allocateDirect(requested);
        output.clear().limit(requested);
        final int length;
        try { length = decoder.read(output); }
        catch (IOException failure) { eof = true; throw AssetLoadException.content("Invalid Ogg audio stream", failure); }
        if (length == 0) { eof = true; return EMPTY.duplicate(); }
        output.flip(); return output.slice();
    }
    @Override public AudioFormat getFormat() { return format; }
    @Override public boolean isClosed() { return closed; }
    @Override public void close() {
        if (!closed) { closed = true; decoder.close(); output = null; }
    }
    static SupportedAudioProbe.MediaInfo inspect(ByteBuffer data, SupportedAudioProbe.Encoding encoding)
            throws UnsupportedAudioFileException {
        var inspection = SupportedAudioProbe.inspect(data);
        if (!inspection.playable() || inspection.media().encoding() != encoding)
            throw new UnsupportedAudioFileException(inspection.diagnostic());
        return inspection.media();
    }
}
