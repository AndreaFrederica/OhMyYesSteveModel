package com.elfmcys.ysm.format.schema.model.views;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerView;
import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import com.elfmcys.ysm.format.schema.file.AssetFileView;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.proto.mixel.common.Sound;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Immutable, metadata-only view of one declared model sound stream. */
public final class SoundStreamView {
    private final String name;
    private final SupportedAudioProbe.Encoding encoding;
    private final int channels;
    private final long sampleRate;
    private final long frames;
    private final int streamId;
    private final AssetContainerView.ChunkInfo chunk;
    private final StreamLocator locator;

    static SoundStreamView create(Sound sound,
                                  AssetFileView fileView) throws IOException {
        Objects.requireNonNull(sound, "sound");
        Objects.requireNonNull(fileView, "fileView");
        requireName(sound.name());

        final SupportedAudioProbe.Encoding encoding;
        try {
            encoding = SupportedAudioProbe.Encoding.valueOf(sound.encoding());
        } catch (IllegalArgumentException error) {
            throw new IOException("Sound uses an unknown encoding token: " + sound.name(), error);
        }
        int channels = sound.channels();
        if (channels != 1 && channels != 2) {
            throw new IOException("Sound has invalid channel count: " + sound.name());
        }
        long sampleRate = Integer.toUnsignedLong(sound.sampleRate());
        if (sampleRate == 0
                || encoding == SupportedAudioProbe.Encoding.OGG_OPUS
                && sampleRate != 48_000) {
            throw new IOException("Sound has invalid sample rate: " + sound.name());
        }
        long frames = sound.samples();
        if (frames < 0) {
            throw new IOException("Sound frame count exceeds the supported range: " + sound.name());
        }
        int streamId = sound.streamId();
        if (streamId == 0) {
            throw new IOException("Sound has no stream id: " + sound.name());
        }
        var chunk = fileView.streamInfo(streamId);
        if (chunk == null) {
            throw new IOException("Sound stream is missing: " + sound.name());
        }
        if (!chunk.encoding().isEmpty() || chunk.decodeSize() != 0 || chunk.flags() != 0) {
            throw new IOException("Sound stream does not use direct storage: " + sound.name());
        }
        if (chunk.hash() == null) {
            throw new IOException("Sound stream has no logical hash: " + sound.name());
        }
        return new SoundStreamView(sound.name(), encoding, channels, sampleRate, frames,
                streamId, chunk);
    }

    private SoundStreamView(String name, SupportedAudioProbe.Encoding encoding,
                            int channels, long sampleRate, long frames, int streamId,
                            AssetContainerView.ChunkInfo chunk) {
        this.name = name;
        this.encoding = encoding;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.frames = frames;
        this.streamId = streamId;
        this.chunk = copyChunk(chunk);
        this.locator = new StreamLocator(chunk.type(), chunk.size(), chunk.hash());
    }

    public SupportedAudioProbe.Inspection admit(ByteBuffer encoded) {
        return SupportedAudioProbe.admit(encoded, encoding, channels, sampleRate, frames);
    }

    /** The caller must pass content for the exact representation that produced this view. */
    public AdmittedSound readVerified(BooleanSupplier cancelled,
                                      ChunkDataSource source) throws IOException {
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(source, "source");
        UniBuffer encoded = source.readPayload(cancelled, chunk, BufferType.NATIVE);
        try {
            var inspection = admit(encoded.nio());
            if (!inspection.playable()) {
                throw AssetLoadException.content("Invalid sound " + name + ": "
                        + inspection.disposition() + ": " + inspection.diagnostic());
            }
            return new AdmittedSound(encoded, inspection.media());
        } catch (IOException | RuntimeException | Error failure) {
            encoded.close();
            throw failure;
        }
    }

    boolean hasSameMedia(SoundStreamView other) {
        return encoding == other.encoding
                && channels == other.channels
                && sampleRate == other.sampleRate
                && frames == other.frames
                && locator.equals(other.locator);
    }

    public String name() {
        return name;
    }

    public SupportedAudioProbe.Encoding encoding() {
        return encoding;
    }

    public int channels() {
        return channels;
    }

    public long sampleRate() {
        return sampleRate;
    }

    public long frames() {
        return frames;
    }

    public int streamId() {
        return streamId;
    }

    public StreamLocator locator() {
        return locator;
    }

    public AssetContainerView.ChunkInfo chunkInfo() {
        return copyChunk(chunk);
    }

    private static void requireName(String name) throws IOException {
        if (name.isEmpty()) {
            throw new IOException("Sound name is empty");
        }
        try {
            StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(name));
        } catch (CharacterCodingException error) {
            throw new IOException("Sound name is not well-formed UTF-8", error);
        }
    }

    private static AssetContainerView.ChunkInfo copyChunk(
            AssetContainerView.ChunkInfo chunk) {
        return new AssetContainerView.ChunkInfo(chunk.type(), chunk.encoding(),
                chunk.offset(), chunk.size(), chunk.decodeSize(), chunk.flags(),
                chunk.alignSize(), chunk.alignmentShift(), chunk.hash().clone());
    }

    public static final class AdmittedSound implements AutoCloseable {
        private final UniBuffer encoded;
        private final SupportedAudioProbe.MediaInfo media;

        private AdmittedSound(UniBuffer encoded, SupportedAudioProbe.MediaInfo media) {
            this.encoded = encoded;
            this.media = media;
        }

        public ByteBuffer encoded() {
            return encoded.nio().asReadOnlyBuffer();
        }

        public SupportedAudioProbe.MediaInfo media() {
            return media;
        }

        public UniBuffer acquireEncoded() {
            return encoded.acquire();
        }

        @Override
        public void close() {
            encoded.close();
        }
    }

    public static final class StreamLocator {
        private final String chunkType;
        private final int storedSize;
        private final Hash256 logicalHash;

        private StreamLocator(String chunkType, int storedSize, byte[] logicalHash) {
            this.chunkType = Objects.requireNonNull(chunkType, "chunkType");
            this.storedSize = storedSize;
            this.logicalHash = new Hash256(
                    Objects.requireNonNull(logicalHash, "logicalHash"));
        }

        public String chunkType() {
            return chunkType;
        }

        public int storedSize() {
            return storedSize;
        }

        public Hash256 logicalHash() {
            return logicalHash;
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof StreamLocator other
                    && storedSize == other.storedSize
                    && chunkType.equals(other.chunkType)
                    && logicalHash.equals(other.logicalHash);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * chunkType.hashCode() + storedSize)
                    + logicalHash.hashCode();
        }
    }
}
