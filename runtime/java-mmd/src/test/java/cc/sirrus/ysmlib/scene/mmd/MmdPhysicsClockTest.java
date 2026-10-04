package cc.sirrus.ysmlib.scene.mmd;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MmdPhysicsClockTest {
  @Test void fractionalFramesAccumulateAndLongFramesDropWholeSteps() {
    var clock=new MmdPhysicsClock(60,4);int steps=0;
    for(int i=0;i<120;i++)steps+=clock.consume(1d/120).steps();assertEquals(60,steps);
    var stall=clock.consume(.503);assertEquals(4,stall.steps());assertEquals(.003,stall.remainderSeconds(),1e-9);assertEquals(26d/60,stall.discardedSeconds(),1e-9);
    assertEquals(1,clock.consume(1d/60-.003).steps());clock.reset();assertEquals(0,clock.consume(0).steps());
  }
  @Test void hugeFiniteDeltaAndRejectedDeltaDoNotCorruptRemainder() {
    var clock=new MmdPhysicsClock(60,4);clock.consume(.001);assertThrows(IllegalArgumentException.class,()->clock.consume(Double.NaN));assertThrows(IllegalArgumentException.class,()->clock.consume(-1));
    assertEquals(1,clock.consume(1d/60-.001).steps());var huge=clock.consume(Double.MAX_VALUE);assertEquals(4,huge.steps());assertTrue(Double.isFinite(huge.discardedSeconds()));assertTrue(huge.remainderSeconds()>=0 && huge.remainderSeconds()<1d/60);
  }
}
