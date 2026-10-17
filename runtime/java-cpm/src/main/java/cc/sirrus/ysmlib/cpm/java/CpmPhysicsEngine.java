package cc.sirrus.ysmlib.cpm.java;

import java.util.LinkedHashMap;
import java.util.Map;

/** CPM physicalize solver expressed in YSM local pose space. */
final class CpmPhysicsEngine {
    private final Map<String, State> states = new LinkedHashMap<>();
    void reset() { states.clear(); }

    double[] apply(String bone, double[] authored, double[] parameters, double seconds) {
        if (parameters == null || parameters.length < 5) return authored;
        var state = states.computeIfAbsent(bone, ignored -> new State(authored));
        double dt = state.time == Double.NEGATIVE_INFINITY ? .05 : seconds - state.time;
        if (!Double.isFinite(dt) || dt <= 0 || dt > .25) dt = .05;
        state.time = seconds;
        // physicalize(elasticity, stiffness, damping, friction, gravity).
        double elasticity = finite(parameters[0]) ? parameters[0] : 0;
        double stiffness = finite(parameters[1]) ? parameters[1] : 0;
        double damping = finite(parameters[2]) ? parameters[2] : 1;
        double gravity = finite(parameters[4]) ? parameters[4] : 0;
        damping = Math.max(0, Math.min(1, damping));
        for (int axis = 0; axis < 3; axis++) {
            double target = finiteAt(authored, axis);
            double correction = (target - state.value[axis]) * (elasticity / 16d);
            double attraction = (target - state.value[axis]) * (stiffness / 16d);
            state.velocity[axis] += (correction + attraction) * dt * 20d;
            if (axis == 1) state.velocity[axis] -= gravity * dt;
            state.velocity[axis] *= damping;
            state.value[axis] += state.velocity[axis] * dt;
            if (!Double.isFinite(state.value[axis])) state.value[axis] = target;
        }
        return state.value.clone();
    }
    private static double finiteAt(double[] values, int index) {
        return values != null && values.length > index && finite(values[index]) ? values[index] : 0;
    }
    private static boolean finite(double value) { return Double.isFinite(value); }
    private static final class State {
        final double[] value = new double[3];
        final double[] velocity = new double[3];
        double time = Double.NEGATIVE_INFINITY;
        State(double[] authored) { for (int i = 0; i < 3; i++) value[i] = finiteAt(authored, i); }
    }
}
