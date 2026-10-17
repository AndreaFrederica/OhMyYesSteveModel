package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import java.util.*;

/** Format dispatch only. Legacy and Modern pose semantics live in separate sessions. */
final class JavaCpmPoseSession implements CpmPoseSession {
    private final CpmPoseSession delegate;
    JavaCpmPoseSession(CpmDocument source) {
        delegate = source instanceof CpmDocument.Legacy
                ? new JavaCpmLegacyPoseSession((CpmDocument.Legacy) source)
                : new JavaCpmModernPoseSession((CpmDocument.Remake) source);
    }
    @Override public void init(double age) { delegate.init(age); }
    @Override public void setHostPose(Map<String, BonePose> hostPose) { delegate.setHostPose(hostPose); }
    @Override public Map<String, BonePose> tick(double age, Map<String, Double> inputs) { return delegate.tick(age, inputs); }
    @Override public List<CpmFeatures.Spawn> drainParticles() { return delegate.drainParticles(); }
    @Override public void close() { delegate.close(); }
}
