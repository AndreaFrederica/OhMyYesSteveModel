package cc.sirrus.ysmlib.audio.java;

import cc.sirrus.ysmlib.audio.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

public final class JavaAudioProvider implements AudioProvider {
    @Override public String id() { return "java-opus-vorbis"; }
    @Override public PcmStream open(ByteBuffer encoded, SupportedAudioProbe.MediaInfo expected) throws IOException {
        Objects.requireNonNull(expected);
        if (encoded.remaining() > 256 * 1024 * 1024) throw new IOException("Audio input budget exceeded");
        byte[] copy = new byte[encoded.remaining()]; encoded.duplicate().get(copy);
        ByteBuffer owned = ByteBuffer.wrap(copy);
        var admission = SupportedAudioProbe.inspect(owned);
        if (!admission.playable()) throw new IOException(admission.diagnostic());
        if (!admission.media().equals(expected)) throw new IOException("Audio metadata mismatch");
        try {
            var packets = new OggPackets(owned);
            return expected.encoding() == SupportedAudioProbe.Encoding.OGG_OPUS
                    ? new OpusPcmStream(packets, expected) : new VorbisPcmStream(packets, expected);
        } catch (RuntimeException invalid) { throw new IOException("Invalid audio headers", invalid); }
    }
}
