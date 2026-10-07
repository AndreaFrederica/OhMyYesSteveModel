package com.elfmcys.ysm.client.sound.codec;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.sound.stream.CustomAudioStream;
import org.jetbrains.annotations.Nullable;
import paulscode.sound.ICodec;
import paulscode.sound.SoundBuffer;
import paulscode.sound.SoundSystemConfig;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.ByteBuffer;

public class YsmAudioCodec implements ICodec {
    private boolean reverseBytes;
    @Nullable
    private CustomAudioStream stream;
    private boolean initialized;
    private boolean endOfStream;

    @Override
    public void reverseByteOrder(boolean reverseBytes) {
        this.reverseBytes = reverseBytes;
    }

    @Override
    public boolean initialize(URL url) {
        cleanup();
        var provider = YsmAudioUrl.providerOf(url);
        if (provider == null) {
            YesSteveModel.LOGGER.error("Not a ysm audio url: {}", url);
            return false;
        }
        try {
            // ICodec 是 paulscode 的同步回调，必须在此阻塞拿流；循环语义由 SoundManager 的
            // toLoop 实参承担（paulscode 负责 repeat），因此 looping 恒为 false
            stream = provider.openStream(false).join();
        } catch (Exception error) {
            YesSteveModel.LOGGER.error("Failed to open the custom audio stream", error);
            return false;
        }
        initialized = true;
        endOfStream = false;
        return true;
    }

    @Override
    public boolean initialized() {
        return initialized;
    }

    @Override
    public SoundBuffer read() {
        var stream = this.stream;
        if (stream == null || endOfStream) {
            return null;
        }
        var format = stream.getFormat();
        ByteBuffer byteBuffer;
        try {
            byteBuffer = stream.read(SoundSystemConfig.getStreamingBufferSize());
        } catch (Exception error) {
            YesSteveModel.LOGGER.error("Failed to read the custom audio stream", error);
            endOfStream = true;
            return null;
        }
        if (!byteBuffer.hasRemaining()) {
            endOfStream = true;
            return null;
        }
        return new SoundBuffer(toArray(byteBuffer, format), format);
    }

    @Override
    public SoundBuffer readAll() {
        var format = stream == null ? null : stream.getFormat();
        var output = new ByteArrayOutputStream();
        SoundBuffer chunk;
        while ((chunk = read()) != null) {
            output.write(chunk.audioData, 0, chunk.audioData.length);
            format = chunk.audioFormat;
            chunk.cleanup();
        }
        if (format == null || output.size() == 0) {
            return null;
        }
        return new SoundBuffer(output.toByteArray(), format);
    }

    @Override
    public boolean endOfStream() {
        return endOfStream;
    }

    @Override
    public void cleanup() {
        var stream = this.stream;
        this.stream = null;
        initialized = false;
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception error) {
                YesSteveModel.LOGGER.error("Failed to close the custom audio stream", error);
            }
        }
    }

    @Override
    public AudioFormat getAudioFormat() {
        var stream = this.stream;
        return stream == null ? null : stream.getFormat();
    }

    private byte[] toArray(ByteBuffer byteBuffer, AudioFormat format) {
        var bytes = new byte[byteBuffer.remaining()];
        byteBuffer.duplicate().get(bytes);
        // 一般不会走到：OpenAL 侧不要求反转，PCM 已是小端
        if (reverseBytes && format.getSampleSizeInBits() == 16) {
            for (int i = 0; i + 1 < bytes.length; i += 2) {
                var low = bytes[i];
                bytes[i] = bytes[i + 1];
                bytes[i + 1] = low;
            }
        }
        return bytes;
    }
}
