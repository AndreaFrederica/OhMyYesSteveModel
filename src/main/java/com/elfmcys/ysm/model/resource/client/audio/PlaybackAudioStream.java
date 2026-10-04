package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.client.sound.stream.CustomAudioStream;
import com.elfmcys.ysm.format.AssetLoadException;
import org.jetbrains.annotations.NotNull;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class PlaybackAudioStream implements CustomAudioStream {
    private static final ByteBuffer EMPTY = ByteBuffer.allocateDirect(0).asReadOnlyBuffer();

    private final boolean looping;
    private final PcmPublisher publisher;
    private final BooleanSupplier publicationEligible;
    private final Runnable closedCallback;
    private final Consumer<Throwable> failureCallback;
    private final AudioRetentionCache.Receipt receipt;
    private EncodedAudio encoded;
    private PcmAudio pcm;
    private CustomAudioStream decoder;
    private ByteArrayOutputStream candidate;
    private AudioFormat format;
    private ByteBuffer pcmOutput;
    private int pcmOffset;
    private boolean closed;
    private boolean ended;

    PlaybackAudioStream(CachedAudio audio, boolean looping,
                        AudioRetentionCache.Receipt receipt,
                        BooleanSupplier publicationEligible,
                        PcmPublisher publisher, Runnable closedCallback,
                        Consumer<Throwable> failureCallback)
            throws IOException {
        this.looping = looping;
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        this.publicationEligible = Objects.requireNonNull(
                publicationEligible, "publicationEligible");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.closedCallback = Objects.requireNonNull(closedCallback, "closedCallback");
        this.failureCallback = Objects.requireNonNull(failureCallback, "failureCallback");
        Objects.requireNonNull(audio, "audio");
        try {
            if (audio instanceof PcmAudio ready) {
                pcm = ready;
                format = ready.format();
            } else if (audio instanceof EncodedAudio source) {
                encoded = source;
                decoder = source.openDecoder();
                format = decoder.getFormat();
                candidate = candidate(source);
            } else {
                throw new IllegalArgumentException("Unknown audio representation");
            }
        } catch (Throwable failure) {
            audio.close();
            throw failure;
        }
    }

    @Override
    public synchronized @NotNull AudioFormat getFormat() {
        return format;
    }

    @Override
    public synchronized @NotNull ByteBuffer read(int size) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        if (size == 0 || closed || ended) {
            return EMPTY;
        }
        int requested = size == 1 ? Short.BYTES : size - size % Short.BYTES;
        try {
            if (pcm != null) {
                return readPcm(requested);
            }
            return readEncoded(requested);
        } catch (IOException | RuntimeException | Error failure) {
            failureCallback.accept(failure);
            closeAfterFailure();
            throw failure;
        }
    }

    private ByteBuffer readPcm(int requested) {
        if (pcm.size() == 0) {
            ended = true;
            return EMPTY;
        }
        if (pcmOffset == pcm.size()) {
            if (!looping) {
                ended = true;
                return EMPTY;
            }
            pcmOffset = 0;
        }
        int length = Math.min(requested, pcm.size() - pcmOffset);
        if (pcmOutput == null || pcmOutput.capacity() < length) {
            pcmOutput = ByteBuffer.allocateDirect(length);
        }
        // Minecraft uploads synchronously before reading again. Keep the shared
        // PCM on heap, but give each playback its own reusable OpenAL buffer.
        pcmOutput.clear().limit(length);
        pcmOutput.put(pcm.read(pcmOffset, length)).flip();
        pcmOffset += length;
        return pcmOutput.asReadOnlyBuffer();
    }

    private ByteBuffer readEncoded(int requested) throws IOException {
        while (true) {
            var result = decoder.read(requested);
            if (result.hasRemaining()) {
                appendCandidate(result);
                return result;
            }
            decoder.close();
            decoder = null;
            PcmAudio completed = finishCandidate();
            if (!looping) {
                releaseEncoded();
                ended = true;
                return EMPTY;
            }
            if (completed != null) {
                pcm = completed;
                releaseEncoded();
                return readPcm(requested);
            }
            if (encoded.media().frames() == 0) {
                releaseEncoded();
                ended = true;
                return EMPTY;
            }
            decoder = encoded.openDecoder();
        }
    }

    private void appendCandidate(ByteBuffer result) throws IOException {
        if (candidate == null) {
            return;
        }
        var copy = result.duplicate();
        if ((long) candidate.size() + copy.remaining()
                > Math.multiplyExact(encoded.media().frames(), Short.BYTES)) {
            candidate = null;
            throw AssetLoadException.content(
                    "Decoded audio exceeded the admitted frame count");
        }
        var bytes = new byte[copy.remaining()];
        copy.get(bytes);
        candidate.writeBytes(bytes);
    }

    private PcmAudio finishCandidate() throws IOException {
        if (candidate == null) {
            return null;
        }
        long expected = Math.multiplyExact(encoded.media().frames(), Short.BYTES);
        if (candidate.size() != expected) {
            candidate = null;
            throw AssetLoadException.content(
                    "Decoded audio did not match the admitted frame count");
        }
        var completed = new PcmAudio(candidate.toByteArray(), format);
        candidate = null;
        if (publicationEligible.getAsBoolean()) {
            publisher.publish(completed, receipt);
        }
        return completed;
    }

    private static ByteArrayOutputStream candidate(EncodedAudio encoded) {
        var media = encoded.media();
        long threshold = Math.multiplyExact(4L, media.sampleRate());
        if (media.frames() >= threshold) {
            return null;
        }
        long bytes = Math.multiplyExact(media.frames(), Short.BYTES);
        if (bytes > AudioRetentionCache.DEFAULT_BUDGET || bytes > Integer.MAX_VALUE) {
            return null;
        }
        return new ByteArrayOutputStream((int) Math.min(bytes, 8192));
    }

    @Override
    public void close() throws IOException {
        Runnable callback;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            if (decoder != null) {
                try {
                    decoder.close();
                } catch (IOException error) {
                    failure = error;
                }
                decoder = null;
            }
            releaseEncoded();
            pcm = null;
            pcmOutput = null;
            candidate = null;
            callback = closedCallback;
            if (failure != null) {
                callback.run();
                throw failure;
            }
        }
        callback.run();
    }

    @Override
    public synchronized boolean isClosed() {
        return closed;
    }

    private void closeAfterFailure() {
        try {
            close();
        } catch (IOException ignored) {
        }
    }

    private void releaseEncoded() {
        if (encoded != null) {
            encoded.close();
            encoded = null;
        }
    }

    @FunctionalInterface
    interface PcmPublisher {
        void publish(PcmAudio pcm, AudioRetentionCache.Receipt receipt);
    }
}
