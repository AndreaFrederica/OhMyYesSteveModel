package cc.sirrus.ysmlib.scene;

/** Host-independent transport controls. The host supplies one elapsed time per monotonically numbered update. */
public interface AnimationPreview<T> {
  record Range(double start,double end,double framesPerSecond) {
    public Range {
      if(!Double.isFinite(start)||!Double.isFinite(end)||end<start||!Double.isFinite(end-start)
          ||!Double.isFinite(framesPerSecond)||framesPerSecond<=0||!Double.isFinite((end-start)*framesPerSecond))
        throw new IllegalArgumentException("Invalid preview range/frame rate");
    }
  }
  /** Returns an owned frame. It must deterministically seek/replay in both directions and publish atomically. */
  @FunctionalInterface interface Source<T> { T seek(double seconds); }
  record State(double seconds,boolean playing,double speed,boolean looping) {}
  Range range();
  State state();
  T current();
  void play();
  void pause();
  void speed(double rate);
  void looping(boolean enabled);
  T seek(double seconds);
  /** Move to a source frame boundary relative to range.start, then pause. Zero leaves the state unchanged. */
  T step(int frames);
  /** Repeated sequence numbers return the published frame; no view advances animation or physics twice. */
  T advance(long sequence,double elapsedSeconds);
}
