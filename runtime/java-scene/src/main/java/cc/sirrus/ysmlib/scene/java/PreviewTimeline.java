package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.AnimationPreview;
import java.util.Objects;

/** Single-owner transport. Time changes only after a successful source seek; reading never runs the source. */
public final class PreviewTimeline<T> implements AnimationPreview<T> {
  private final Range range;
  private final Source<T> source;
  private double seconds,speed=1;
  private boolean playing,looping,hasSequence;
  private long lastSequence;
  private T current;
  public PreviewTimeline(Range range,Source<T> source) {
    this.range=Objects.requireNonNull(range);this.source=Objects.requireNonNull(source);seconds=range.start();current=Objects.requireNonNull(source.seek(seconds));
  }
  public Range range() { return range; }
  public State state() { return new State(seconds,playing,speed,looping); }
  public T current() { return current; }
  public void play() { playing=range.end()>range.start(); }
  public void pause() { playing=false; }
  public void speed(double rate) { if(!Double.isFinite(rate)||rate==0) throw new IllegalArgumentException("Preview speed must be finite and nonzero");speed=rate; }
  public void looping(boolean enabled) { looping=enabled; }
  public T seek(double time) {
    if(!Double.isFinite(time)||time<range.start()||time>range.end()) throw new IllegalArgumentException("Preview seek outside source range");
    if(time==seconds) return current;
    T next=Objects.requireNonNull(source.seek(time));seconds=time;current=next;return next;
  }
  public T step(int frames) {
    if(frames==0) return current;
    double index=(seconds-range.start())*range.framesPerSecond(),nearest=Math.rint(index);
    if(Math.abs(index-nearest)<=Math.ulp(index)*8) index=nearest;
    double nextIndex=(frames>0?Math.floor(index):Math.ceil(index))+frames;
    double target=range.start()+nextIndex/range.framesPerSecond();
    T result=seek(Math.max(range.start(),Math.min(range.end(),target)));playing=false;return result;
  }
  public T advance(long sequence,double elapsedSeconds) {
    if(!Double.isFinite(elapsedSeconds)||elapsedSeconds<0) throw new IllegalArgumentException("Invalid elapsed preview time");
    if(hasSequence) {
      if(sequence<lastSequence) throw new IllegalArgumentException("Preview update sequence moved backwards");
      if(sequence==lastSequence) return current;
    }
    boolean nextPlaying=playing;
    if(playing&&elapsedSeconds!=0) {
      double target=seconds+elapsedSeconds*speed;if(!Double.isFinite(target)) throw new IllegalArgumentException("Preview time overflow");
      if(looping&&range.end()>range.start()) {
        double length=range.end()-range.start(),offset=target-range.start();
        if(!Double.isFinite(offset)) throw new IllegalArgumentException("Preview loop offset overflow");
        double remainder=offset%length;if(remainder<0) remainder+=length;target=range.start()+remainder;
      } else {
        target=Math.max(range.start(),Math.min(range.end(),target));
        if(speed>0&&target==range.end() || speed<0&&target==range.start()) nextPlaying=false;
      }
      seek(target);
    }
    lastSequence=sequence;hasSequence=true;playing=nextPlaying;return current;
  }
}
