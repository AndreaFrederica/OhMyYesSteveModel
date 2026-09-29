package cc.sirrus.ysmlib.audio.java;

import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.*;
import java.io.IOException;

final class VorbisPcmStream extends PacketPcmStream {
    private final Info info = new Info();
    private final Comment comments = new Comment();
    private final DspState dsp = new DspState();
    private final Block block = new Block(dsp);
    private final int channels;
    private long packetNumber;
    private boolean released;

    VorbisPcmStream(OggPackets packets, SupportedAudioProbe.MediaInfo media) throws IOException {
        super(packets, media.frames()); channels = media.channels();
        info.init(); comments.init();
        for (int i = 0; i < 3; i++) {
            if (info.synthesis_headerin(comments, packet(packets.next())) != 0) throw new IOException("Invalid Vorbis headers");
        }
        if (info.channels != channels || info.rate != media.sampleRate()) throw new IOException("Vorbis metadata mismatch");
        if (dsp.synthesis_init(info) != 0) throw new IOException("Invalid Vorbis setup");
        block.init(dsp);
    }

    private Packet packet(byte[] bytes) {
        var result = new Packet(); result.packet_base = bytes; result.packet = 0; result.bytes = bytes.length;
        result.b_o_s = packetNumber == 0 ? 1 : 0; result.e_o_s = 0;
        result.granulepos = -1; result.packetno = packetNumber++;
        return result;
    }

    @Override protected short[] decodePacket(byte[] bytes) throws IOException {
        if (block.synthesis(packet(bytes)) != 0 || dsp.synthesis_blockin(block) != 0)
            throw new IOException("Invalid Vorbis audio packet");
        float[][][] pcm = new float[1][][]; int[] indices = new int[channels];
        int count = dsp.synthesis_pcmout(pcm, indices);
        short[] result = new short[count];
        for (int i = 0; i < count; i++) {
            int left = sample(pcm[0][0][indices[0] + i]);
            result[i] = channels == 1 ? (short) left : (short) Math.rint(
                    (left + sample(pcm[0][1][indices[1] + i])) / 2.0);
        }
        dsp.synthesis_read(count);
        return result;
    }
    private static int sample(float value) { return Math.max(-32768, Math.min(32767, (int) Math.rint(value * 32768.0))); }
    @Override public void close() {
        if (!released) { released = true; super.close(); block.clear(); dsp.clear(); info.clear(); }
    }
}
