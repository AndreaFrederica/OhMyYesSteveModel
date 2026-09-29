package cc.sirrus.ysmlib.audio.java;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Reads only the private, immutable copy already validated by SupportedAudioProbe. */
final class OggPackets {
    private final ByteBuffer input;
    private int segment, segmentCount, table, body;
    private boolean finalPage;
    OggPackets(ByteBuffer admitted) { input = admitted.duplicate(); }

    byte[] next() throws IOException {
        var output = new ByteArrayOutputStream();
        while (true) {
            if (segment == segmentCount) {
                if (!input.hasRemaining()) {
                    if (output.size() != 0) throw new IOException("Incomplete Ogg packet");
                    return null;
                }
                int page = input.position();
                finalPage = (input.get(page + 5) & 4) != 0;
                segmentCount = input.get(page + 26) & 255;
                segment = 0; table = page + 27; body = table + segmentCount;
                int bodyLength = 0;
                for (int i = 0; i < segmentCount; i++) bodyLength += input.get(table + i) & 255;
                input.position(body + bodyLength);
                if (segmentCount == 0) continue;
            }
            int length = input.get(table + segment++) & 255;
            output.write(input.array(), input.arrayOffset() + body, length);
            body += length;
            if (length < 255) return output.toByteArray();
        }
    }
    boolean onFinalPage() { return finalPage; }
}
