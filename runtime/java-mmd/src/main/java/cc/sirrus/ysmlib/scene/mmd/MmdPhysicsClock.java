package cc.sirrus.ysmlib.scene.mmd;

/** Explicit live fixed-step budget. Preview seek/replay keeps its existing full-history clock. */
public final class MmdPhysicsClock {
  public record Tick(int steps,double remainderSeconds,double discardedSeconds) {}
  private final double stepSeconds;
  private final int maxSubsteps;
  private double remainder;
  public MmdPhysicsClock(int frequencyHz,int maxSubsteps) {
    if(frequencyHz<1 || frequencyHz>1000 || maxSubsteps<1 || maxSubsteps>1000)throw new IllegalArgumentException("Invalid fixed-step budget");
    stepSeconds=1d/frequencyHz;this.maxSubsteps=maxSubsteps;
  }
  public Tick consume(double elapsedSeconds) {
    if(!Double.isFinite(elapsedSeconds) || elapsedSeconds<0)throw new IllegalArgumentException("Invalid physics delta");
    double total=remainder+elapsedSeconds;
    // Compute complete steps separately from the cap, retaining only the fractional remainder.
    double complete=Math.floor(total/stepSeconds+1e-9);
    int steps=(int)Math.min(complete,maxSubsteps);
    double fraction=total%stepSeconds;
    if(fraction<stepSeconds*1e-9 || stepSeconds-fraction<stepSeconds*1e-9)fraction=0;
    double discarded=Math.max(0,total-fraction-steps*stepSeconds);
    remainder=fraction;
    return new Tick(steps,remainder,discarded);
  }
  public void reset() { remainder=0; }
  public double stepSeconds() { return stepSeconds; }
}
