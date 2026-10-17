package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import java.util.*;

/** Legacy CustomModel runtime: expression position, ModelRenderer rotation and tickVars. */
final class JavaCpmLegacyPoseSession implements CpmPoseSession {
    private final CpmDocument.Legacy source;
    private final CpmLegacySession legacy;
    private final CpmPhysicsEngine physics = new CpmPhysicsEngine();
    private final CpmParticleEmitter particles;
    private Map<String, Double> values = Map.of();
    private List<CpmFeatures.Spawn> pending = List.of();
    private long lastTick = Long.MIN_VALUE;
    JavaCpmLegacyPoseSession(CpmDocument.Legacy source) { this.source = Objects.requireNonNull(source); legacy = new CpmLegacySession(source); particles = new CpmParticleEmitter(source); }
    @Override public void init(double age) { }
    @Override public Map<String, BonePose> tick(double age, Map<String, Double> inputs) {
        boolean advance = (long)Math.floor(age) != lastTick;
        if (advance) { lastTick = (long)Math.floor(age); values = legacy.tick(inputs == null ? Map.of() : inputs); }
        var result = new LinkedHashMap<String, BonePose>(); double seconds = age / 20d;
        CpmLegacyPoseEvaluator.evaluate(source, values).forEach((name, pose) -> {
            var rotation = physics.apply(name, pose.rotation(), legacyPhysics(name, values), seconds);
            result.put(name, new BonePose(pose.position(), rotation, pose.scale(), pose.visible(), pose.color(), pose.alpha(), pose.emissive()));
        });
        if (advance) pending = particles.tick(age, values);
        return Map.copyOf(result);
    }
    @Override public List<CpmFeatures.Spawn> drainParticles() { var out = pending; pending = List.of(); return out; }
    private double[] legacyPhysics(String name, Map<String, Double> inputs) {
        for (var bone : source.bones()) if (bone.id().equals(name)) {
            var value = bone.source().fields().get("physics");
            if (!(value instanceof CpmValue.ArrayValue a) || a.values().size() != 5) return null;
            var out = new double[5];
            for (int i=0;i<5;i++) { var item=a.values().get(i); out[i]=item instanceof CpmValue.NumberValue n ? parse(n.value()) : item instanceof CpmValue.BooleanValue b ? (b.value()?1:0) : item instanceof CpmValue.StringValue s ? eval(s.value(), inputs) : 0; }
            return out;
        }
        return null;
    }
    private static double parse(String s) { try { return Double.parseDouble(s); } catch (RuntimeException e) { return 0; } }
    private static double eval(String s, Map<String, Double> in) { try { return CpmExpressionEvaluator.evaluate(s, in); } catch (RuntimeException e) { return 0; } }
}
