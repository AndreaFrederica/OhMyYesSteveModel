package cc.sirrus.ysmlib.cpm;

import java.util.Map;
import java.util.List;

/** Per-entity CPM pose state. Implementations own tick variables and script state. */
public interface CpmPoseSession extends AutoCloseable {
    void init(double age);

    /**
     * Supplies the current host skeleton pose before a CPM script update.
     * CustomPlayerModel exposes vanilla skeleton bones through
     * {@code model.getBone("head")}, {@code "left_arm"}, etc.; a host must
     * provide those values so scripts can read/modify the live player pose.
     * Implementations that do not expose host bones may ignore this hook.
     */
    default void setHostPose(Map<String, BonePose> hostPose) { }

    Map<String, BonePose> tick(double age, Map<String, Double> inputs);

    /** Particle events produced during the most recent tick. Hosts drain this
     * once per logical frame, so secondary render passes cannot duplicate them. */
    default List<CpmFeatures.Spawn> drainParticles() { return List.of(); }

    @Override
    default void close() { }

    record BonePose(double[] position, double[] rotation, double[] scale,
                    boolean visible, double[] color, double alpha, boolean emissive) {
        public BonePose {
            position = copy(position, 3, 0);
            rotation = copy(rotation, 3, 0);
            scale = copy(scale, 3, 1);
            color = copy(color, 3, 1);
            alpha = Double.isFinite(alpha) ? Math.max(0, Math.min(1, alpha)) : 1;
        }

        private static double[] copy(double[] value, int size, double fallback) {
            var result = new double[size];
            java.util.Arrays.fill(result, fallback);
            if (value != null) for (int i = 0; i < Math.min(size, value.length); i++)
                if (Double.isFinite(value[i])) result[i] = value[i];
            return result;
        }
    }
}
