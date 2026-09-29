package cc.sirrus.ysmlib;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** JVM diagnostics independent of the game and optional native accelerators. */
public final class RuntimeProfiler {
  private RuntimeProfiler() {}

  @Name("cc.sirrus.ysmlib.Operation")
  @Label("YSM runtime operation")
  @Category("YSM")
  public static final class Operation extends Event {
    @Label("Operation")
    public String operation;
  }

  public static Scope begin(String name) {
    var event = new Operation();
    if (!event.isEnabled()) return Scope.DISABLED;
    event.operation = name;
    event.begin();
    return new Scope(event);
  }

  public static final class Scope implements AutoCloseable {
    private static final Scope DISABLED = new Scope(null);
    private Operation event;

    private Scope(Operation event) {
      this.event = event;
    }

    @Override
    public void close() {
      if (event != null) {
        event.end();
        event.commit();
        event = null;
      }
    }
  }
}
