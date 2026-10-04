package cc.sirrus.ysmlib.scene;

import java.util.List;
import java.util.Objects;

/** Parsing, evaluation and rendering coverage are deliberately independent. */
public record CompatibilityReport(List<Feature> features,List<Diagnostic> diagnostics) {
  public enum Level { READ, EVALUATED, RENDERED, EXTERNAL_EFFECT, UNSUPPORTED }
  public enum Severity { INFO, WARNING, ERROR }
  public record Feature(String id,Level level,boolean required,String detail) {
    public Feature { Objects.requireNonNull(id);Objects.requireNonNull(level);Objects.requireNonNull(detail); }
  }
  public record Diagnostic(Severity severity,String location,String message) {
    public Diagnostic { Objects.requireNonNull(severity);Objects.requireNonNull(location);Objects.requireNonNull(message); }
  }
  public CompatibilityReport { features=List.copyOf(features);diagnostics=List.copyOf(diagnostics); }
  public boolean canEvaluateRequiredFeatures() {
    return diagnostics.stream().noneMatch(d->d.severity()==Severity.ERROR)
        && features.stream().noneMatch(f->f.required() && (f.level()==Level.UNSUPPORTED || f.level()==Level.READ));
  }
}
