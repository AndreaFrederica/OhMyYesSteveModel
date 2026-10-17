package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.util.Objects;

/**
 * Keeps native FBX evaluation opportunistic. A malformed animation sample is
 * a source compatibility problem, not a reason to terminate the Minecraft
 * render thread: retry the resident native scene at its default/rest
 * animation, then fall back to the checked Java evaluator when the native
 * exporter path cannot evaluate even the rest pose.
 */
public final class FbxResilientPlayer implements FbxEvaluation {
  private final FbxDocument source;
  private final ReadLimits limits;
  private final Settings settings;
  private FbxEvaluation active;
  private FbxEvaluation javaFallback;
  private boolean restFallback;
  private boolean closed;

  public FbxResilientPlayer(FbxDocument source, ReadLimits limits, Settings settings, FbxEvaluation nativePlayer) {
    this.source = Objects.requireNonNull(source);
    this.limits = Objects.requireNonNull(limits);
    this.settings = Objects.requireNonNull(settings);
    this.active = Objects.requireNonNull(nativePlayer);
  }

  @Override public synchronized Frame evaluate(int stack, double seconds) {
    if (closed) throw new IllegalStateException("FBX player is closed");
    try {
      return active.evaluate(restFallback ? -1 : stack, seconds);
    } catch (RuntimeException nativeFailure) {
      if (restFallback || !(active instanceof FbxNativePlayer)) throw nativeFailure;
      restFallback = true;
      try {
        return active.evaluate(-1, seconds);
      } catch (RuntimeException fallbackFailure) {
        try {
          if (javaFallback == null) javaFallback = new FbxPlayer(source, limits, settings);
          var recovered = javaFallback;
          javaFallback = null;
          active = recovered;
          return active.evaluate(-1, seconds);
        } catch (RuntimeException | Error javaFailure) {
          javaFailure.addSuppressed(fallbackFailure);
          javaFailure.addSuppressed(nativeFailure);
          throw javaFailure;
        } catch (Exception javaFailure) {
          fallbackFailure.addSuppressed(javaFailure);
          fallbackFailure.addSuppressed(nativeFailure);
          throw fallbackFailure;
        }
      }
    }
  }

  @Override public synchronized void close() {
    if (closed) return;
    closed = true;
    RuntimeException failure = null;
    try { active.close(); } catch (RuntimeException error) { failure = error; }
    if (javaFallback != null) try { javaFallback.close(); }
    catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
    if (failure != null) throw failure;
  }
}
