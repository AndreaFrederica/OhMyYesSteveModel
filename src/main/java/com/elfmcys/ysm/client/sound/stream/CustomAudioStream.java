package com.elfmcys.ysm.client.sound.stream;

import org.jetbrains.annotations.NotNull;

import javax.sound.sampled.AudioFormat;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

public interface CustomAudioStream extends Closeable {
    @NotNull
    AudioFormat getFormat();

    @NotNull
    ByteBuffer read(int size) throws IOException;

    @Override
    void close() throws IOException;

    boolean isClosed();
}
