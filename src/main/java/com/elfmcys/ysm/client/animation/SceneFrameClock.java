package com.elfmcys.ysm.client.animation;

/** Instance-owned host clock. Secondary render passes may supply different partial ticks. */
public final class SceneFrameClock {
    private boolean initialized;
    private long sequence;
    private double seconds;

    /** Accept one update per display frame, retaining the time high-water mark across interpolation jitter. */
    public boolean beginFrame(long nextSequence, double sampledSeconds) {
        if (!Double.isFinite(sampledSeconds)) throw new IllegalArgumentException("Invalid scene host time");
        if (initialized && nextSequence < sequence) throw new IllegalArgumentException("Scene host frame moved backwards");
        if (initialized && nextSequence == sequence) return false;
        seconds = initialized ? Math.max(seconds, sampledSeconds) : sampledSeconds;
        sequence = nextSequence;
        initialized = true;
        return true;
    }

    public double seconds() {
        if (!initialized) throw new IllegalStateException("Scene host clock has no frame");
        return seconds;
    }
}
