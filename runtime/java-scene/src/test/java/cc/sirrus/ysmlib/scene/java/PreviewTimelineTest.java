package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class PreviewTimelineTest {
  @Test void multipleViewsPauseAndFrameSteppingNeverAdvancePhysicsImplicitly() {
    var seeks=new ArrayList<Double>();var preview=new PreviewTimeline<>(new AnimationPreview.Range(1.0/24,25.0/24,24),t->{ seeks.add(t);return t; });
    preview.play();double next=preview.advance(7,.1);assertEquals(1.0/24+.1,next,1e-12);
    for(int i=0;i<5;i++) { assertEquals(next,preview.current());assertEquals(next,preview.advance(7,.1)); }
    assertEquals(2,seeks.size());preview.pause();preview.advance(8,10);assertEquals(2,seeks.size());
    assertEquals(4.0/24,preview.step(1),1e-12);assertEquals(3.0/24,preview.step(-1),1e-12);assertFalse(preview.state().playing());
    assertThrows(IllegalArgumentException.class,()->preview.advance(6,0));
  }
  @Test void loopsReverseAndEndClampingUseTheSameDeterministicSeekSource() {
    var seeks=new ArrayList<Double>();var preview=new PreviewTimeline<>(new AnimationPreview.Range(0,1,30),t->{ seeks.add(t);return t; });
    preview.play();assertEquals(1,preview.advance(0,2));assertFalse(preview.state().playing());
    preview.speed(-2);preview.play();assertEquals(.75,preview.advance(1,.125));
    preview.looping(true);assertEquals(.25,preview.advance(2,.75));assertTrue(preview.state().playing());
    preview.seek(.8);assertEquals(.75,preview.advance(3,.025),1e-12);
    assertEquals(List.of(0.0,1.0,.75,.25,.8,.75),seeks);
  }
  @Test void failedSeekLeavesPublishedFrameTimeTransportAndSequenceRetryable() {
    var fail=new AtomicBoolean(true);var preview=new PreviewTimeline<>(new AnimationPreview.Range(0,1,30),t->{
      if(t==.5&&fail.get()) throw new IllegalStateException("source failed");return t;
    });preview.play();var before=preview.state();
    assertThrows(IllegalStateException.class,()->preview.advance(11,.5));assertEquals(before,preview.state());assertEquals(0,preview.current());
    fail.set(false);assertEquals(.5,preview.advance(11,.5));assertEquals(.5,preview.advance(11,.5));
    assertThrows(IllegalArgumentException.class,()->preview.seek(2));assertEquals(.5,preview.current());
  }
}
