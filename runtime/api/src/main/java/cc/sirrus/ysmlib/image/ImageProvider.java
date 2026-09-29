package cc.sirrus.ysmlib.image;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface ImageProvider {
    enum Format { RGBA, PNG, JPEG, WEBP, AVIF, ZTX }
    record Info(Format format, int width, int height) {
        public Info {
            if (format == null || width < 1 || height < 1 || width > 65535 || height > 65535
                    || (long) width * height * 4 > 256 * 1024 * 1024) {
                throw new IllegalArgumentException("Invalid or oversized image");
            }
        }
        public int pixelBytes() { return width * height * 4; }
    }
    record Encoded(Info info, byte[] bytes) {}
    String id();
    Info probe(ByteBuffer encoded) throws IOException;
    byte[] decode(ByteBuffer encoded, Info expected) throws IOException;
    Encoded encode(ByteBuffer rgba, int width, int height, int maxWidth, int maxHeight) throws IOException;
}
