package cc.sirrus.ysmlib.scene;

/** Maps elapsed playback time to source time without rewinding a physics clock. */
public record AnimationPlaybackRange(double start, double end, boolean loop) {
  public AnimationPlaybackRange {
    if (!Double.isFinite(start) || !Double.isFinite(end) || end < start || !Double.isFinite(end - start))
      throw new IllegalArgumentException("Invalid animation playback range");
  }
  public double sample(double elapsed) {
    if (!Double.isFinite(elapsed) || elapsed < 0) throw new IllegalArgumentException("Invalid elapsed time");
    double duration = end - start;
    return start + (duration == 0 ? 0 : loop ? elapsed % duration : Math.min(elapsed, duration));
  }
}
