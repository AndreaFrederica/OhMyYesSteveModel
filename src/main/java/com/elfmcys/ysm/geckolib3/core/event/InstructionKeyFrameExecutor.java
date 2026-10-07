package com.elfmcys.ysm.geckolib3.core.event;

import com.elfmcys.ysm.geckolib3.core.keyframe.event.EventKeyFrame;
import com.elfmcys.ysm.geckolib3.core.molang.context.MolangContext;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;

import java.util.List;

public class InstructionKeyFrameExecutor {
    private final List<EventKeyFrame<IValue[]>> list;
    private int nextIndex = 0;

    public InstructionKeyFrameExecutor(List<EventKeyFrame<IValue[]>> list) {
        this.list = list;
    }

    private void evalValues(ExpressionEvaluator<?> evaluator, IValue[] values) {
        for (IValue value : values) {
            value.eval(evaluator);
        }
    }

    public void executeTo(ExpressionEvaluator<MolangContext<?>> evaluator, float currentTick, boolean allowEmitting) {
        evaluator.entity().setAllowEmitting(allowEmitting);
        try {
            while (!reachEnd()) {
                EventKeyFrame<IValue[]> keyFrame = list.get(nextIndex);
                if (keyFrame.getStartTick() > currentTick) {
                    break;
                }
                evalValues(evaluator, keyFrame.getEventData());
                nextIndex++;
            }
        } finally {
            evaluator.entity().setAllowEmitting(false);
        }
    }

    public void executeRemaining(ExpressionEvaluator<MolangContext<?>> evaluator, boolean allowEmitting) {
        evaluator.entity().setAllowEmitting(allowEmitting);
        try {
            for (int i = nextIndex; i < list.size(); i++) {
                evalValues(evaluator, list.get(i).getEventData());
            }
            nextIndex = list.size();
        } finally {
            evaluator.entity().setAllowEmitting(false);
        }
    }

    public boolean reachEnd() {
        return nextIndex >= list.size();
    }

    public void reset() {
        nextIndex = 0;
    }
}
