package cc.sirrus.ysmlib.audio.java;

import cc.sirrus.ysmlib.audio.PcmStream;
import java.io.IOException;
import java.nio.ByteBuffer;

abstract class PacketPcmStream implements PcmStream {
    protected OggPackets packets;
    private final long expectedFrames;
    private long emitted;
    private long decoded;
    private short[] pending = new short[0];
    private int offset;
    private boolean eof, closed;
    private IOException failure;

    PacketPcmStream(OggPackets packets, long frames) { this.packets = packets; expectedFrames = frames; }
    protected abstract short[] decodePacket(byte[] packet) throws IOException;

    @Override public int read(ByteBuffer destination) throws IOException {
        if (closed) throw new IllegalStateException("Audio decoder is closed");
        if (failure != null) throw new IOException("Audio decoder previously failed", failure);
        if (destination.isReadOnly()) throw new IllegalArgumentException("Read-only PCM destination");
        if (destination.remaining() < 2) throw new IllegalArgumentException("PCM destination must fit a frame");
        int initial = destination.position();
        try {
            while (destination.remaining() >= 2) {
                if (offset < pending.length && emitted < expectedFrames) {
                    short value = pending[offset++];
                    destination.put((byte) value).put((byte) (value >>> 8)); emitted++;
                    continue;
                }
                if (eof) break;
                // Decode through EOS even when the final page trims all remaining samples.
                // This preserves full content validation and bounds PCM storage to one packet.
                byte[] packet = packets.next();
                if (packet == null) {
                    eof = true;
                    if (emitted != expectedFrames) throw new IOException("Audio frame count differs from inspected timeline");
                    break;
                }
                pending = decodePacket(packet); offset = 0;
                decoded = Math.addExact(decoded, pending.length);
                if (decoded > expectedFrames && !packets.onFinalPage())
                    throw new IOException("Audio trimming precedes final page");
            }
        } catch (IOException invalid) { failure = invalid; throw invalid; }
        catch (RuntimeException invalid) {
            failure = new IOException("Invalid audio packet", invalid); throw failure;
        }
        return destination.position() - initial;
    }

    @Override public void close() { closed = true; packets = null; pending = new short[0]; }
}
