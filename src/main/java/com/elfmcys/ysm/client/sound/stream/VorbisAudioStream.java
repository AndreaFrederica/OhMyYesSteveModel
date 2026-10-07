package com.elfmcys.ysm.client.sound.stream;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.media.SupportedAudioProbe;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.BufferUtils;
import paulscode.sound.SoundBuffer;
import paulscode.sound.codecs.CodecJOrbis;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class VorbisAudioStream implements CustomAudioStream {
    private static final ByteBuffer EMPTY_BUFFER = BufferUtils.createByteBuffer(0);

    private final CodecJOrbis decoder;
    private final AudioFormat audioFormat;
    private final int channels;
    private final long expectedFrames;
    private long decodedFrames;
    private volatile boolean closed;
    private boolean eof;

    public VorbisAudioStream(ByteBuffer data) throws IOException, UnsupportedAudioFileException {
        this(data, inspect(data));
    }

    public VorbisAudioStream(ByteBuffer data, SupportedAudioProbe.MediaInfo media)
            throws IOException, UnsupportedAudioFileException {
        if (media.encoding() != SupportedAudioProbe.Encoding.OGG_VORBIS) {
            throw new UnsupportedAudioFileException("media is not Ogg Vorbis");
        }
        float representedRate = (float) media.sampleRate();
        if ((long) representedRate != media.sampleRate()) {
            throw new UnsupportedAudioFileException("Vorbis sample rate is not exactly representable by the host");
        }

        final CodecJOrbis opened = new CodecJOrbis();
        // OpenAL 侧不需要反转字节序，jorbis 输出即为小端
        opened.reverseByteOrder(false);
        try {
            if (!opened.initialize(wrapAsUrl(data))) {
                throw AssetLoadException.content("Invalid Ogg Vorbis stream");
            }
        } catch (RuntimeException failure) {
            throw AssetLoadException.content("Invalid Ogg Vorbis stream", failure);
        }
        var format = opened.getAudioFormat();
        if (format == null || format.getChannels() != media.channels()
                || format.getSampleRate() != representedRate) {
            var failure = AssetLoadException.content(
                    "Vorbis decoder metadata disagrees with inspection");
            try {
                opened.cleanup();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        decoder = opened;
        channels = media.channels();
        expectedFrames = media.frames();
        audioFormat = new AudioFormat(representedRate, 16, 1, true, false);
    }

    @Override
    public @NotNull AudioFormat getFormat() {
        return audioFormat;
    }

    @Override
    public @NotNull ByteBuffer read(int size) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        if (size == 0 || eof || closed) {
            return EMPTY_BUFFER;
        }

        final SoundBuffer decoded;
        try {
            decoded = decoder.read();
        } catch (RuntimeException failure) {
            eof = true;
            throw AssetLoadException.content("Invalid Ogg Vorbis stream", failure);
        }
        if (decoded == null || decoded.audioData == null || decoded.audioData.length == 0) {
            if (decoded != null) {
                decoded.cleanup();
            }
            if (decodedFrames != expectedFrames) {
                eof = true;
                throw AssetLoadException.content(
                        "Vorbis frame count does not match the inspected timeline");
            }
            eof = true;
            return EMPTY_BUFFER;
        }
        var sourceBytes = decoded.audioData;
        var sourceFormat = decoded.audioFormat;
        decoded.cleanup();
        if (sourceFormat == null || sourceFormat.getChannels() != channels
                || sourceFormat.getSampleSizeInBits() != 16) {
            eof = true;
            throw AssetLoadException.content(
                    "Vorbis decoder metadata disagrees with inspection");
        }

        int frameBytes = channels * Short.BYTES;
        if (sourceBytes.length % frameBytes != 0) {
            eof = true;
            throw AssetLoadException.content("Vorbis decoder returned a partial frame");
        }
        long frames = sourceBytes.length / frameBytes;
        long remainingFrames = expectedFrames - decodedFrames;
        if (frames > remainingFrames) {
            // paulscode 不执行 Ogg Vorbis 的 EOS 尾部裁剪，超出 granule 的尾帧在此丢弃
            long trimmed = frames - remainingFrames;
            if (trimmed > expectedFrames / 4 + 4096) {
                eof = true;
                throw AssetLoadException.content(
                        "Vorbis decoder exceeded the inspected frame count");
            }
            sourceBytes = Arrays.copyOf(sourceBytes,
                    Math.toIntExact(remainingFrames * frameBytes));
            frames = remainingFrames;
        }
        decodedFrames += frames;

        var source = ByteBuffer.wrap(sourceBytes).order(ByteOrder.LITTLE_ENDIAN);
        if (channels == 1) {
            return source;
        }

        ByteBuffer destination = BufferUtils.createByteBuffer(source.remaining() / 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        while (source.hasRemaining()) {
            int left = source.getShort();
            int right = source.getShort();
            destination.putShort((short) Math.rint((left + right) / 2.0));
        }
        destination.flip();
        return destination;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            decoder.cleanup();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    private static SupportedAudioProbe.MediaInfo inspect(ByteBuffer data)
            throws UnsupportedAudioFileException {
        var inspection = SupportedAudioProbe.inspect(data);
        if (!inspection.playable()
                || inspection.media().encoding() != SupportedAudioProbe.Encoding.OGG_VORBIS) {
            throw new UnsupportedAudioFileException(inspection.diagnostic());
        }
        return inspection.media();
    }

    private static URL wrapAsUrl(ByteBuffer byteBuffer) {
        var handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return new URLConnection(url) {
                    @Override
                    public void connect() {
                    }

                    @Override
                    public InputStream getInputStream() {
                        return new ByteBufInputStream(Unpooled.wrappedBuffer(byteBuffer.duplicate()));
                    }
                };
            }
        };
        try {
            return URL.of(URI.create("ysmvorbis:memory.ogg"), handler);
        } catch (MalformedURLException impossible) {
            throw new IllegalStateException("Failed to create in-memory vorbis URL", impossible);
        }
    }
}
