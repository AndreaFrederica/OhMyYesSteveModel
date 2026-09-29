package cc.sirrus.ysmlib.audio.java;

import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import java.io.IOException;
import org.concentus.OpusDecoder;
import org.concentus.OpusException;

final class OpusPcmStream extends PacketPcmStream {
    private final OpusDecoder decoder;
    private final int channels;
    private int skip;
    private final short[] buffer;

    OpusPcmStream(OggPackets packets, SupportedAudioProbe.MediaInfo media) throws IOException {
        super(packets, media.frames());
        channels = media.channels(); skip = media.preSkip(); buffer = new short[5760 * channels];
        packets.next(); packets.next(); // Strict identification/tags already admitted.
        try { decoder = new OpusDecoder(48000, channels); decoder.setGain(media.outputGain()); }
        catch (OpusException invalid) { throw new IOException("Invalid Opus configuration", invalid); }
    }

    @Override protected short[] decodePacket(byte[] packet) throws IOException {
        final int count;
        try { count = decoder.decode(packet, 0, packet.length, buffer, 0, 5760, false); }
        catch (OpusException invalid) { throw new IOException("Invalid Opus packet", invalid); }
        int discard = Math.min(skip, count); skip -= discard;
        short[] mono = new short[count - discard];
        for (int frame = discard; frame < count; frame++) {
            int index = frame * channels;
            mono[frame - discard] = channels == 1 ? buffer[index]
                    : (short) Math.rint((buffer[index] + buffer[index + 1]) / 2.0);
        }
        return mono;
    }
}
