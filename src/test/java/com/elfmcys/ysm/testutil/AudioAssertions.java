package com.elfmcys.ysm.testutil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

/** Independent floating-point FFmpeg reference versus the fixed-point JVM decoder. */
public final class AudioAssertions {
    private AudioAssertions() {}
    public static void reference(byte[] expected, byte[] actual, boolean opus, String label) {
        assertEquals(expected.length, actual.length, label + " frame count");
        var a = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN);
        var b = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
        double signal = 0, error = 0;
        int peak = 0;
        while (a.hasRemaining()) {
            int sample = a.getShort(), delta = sample - b.getShort();
            signal += (double) sample * sample;
            error += (double) delta * delta;
            peak = Math.max(peak, Math.abs(delta));
        }
        double snr = error == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signal / error);
        assertTrue(peak <= (opus ? 8 : 2), label + " peak=" + peak + " LSB");
        assertTrue(snr >= 70, label + " SNR=" + snr + " dB");
        System.out.println(label + ": peak=" + peak + " LSB, SNR=" + snr + " dB");
    }
}
