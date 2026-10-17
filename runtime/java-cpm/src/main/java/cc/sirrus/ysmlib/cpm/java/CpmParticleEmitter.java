package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import java.util.*;

/** Deterministic source particle scheduler. Rendering is delegated to the host. */
final class CpmParticleEmitter {
    private final List<CpmFeatures.Particle> definitions;
    private final Map<String, Double> clocks = new LinkedHashMap<>();
    private final Random random = new Random(0x43504dL);
    private double previous = Double.NaN;

    CpmParticleEmitter(CpmDocument source) { definitions = CpmFeatures.particles(source); }
    void reset() { clocks.clear(); previous = Double.NaN; }

    List<CpmFeatures.Spawn> tick(double age, Map<String, Double> inputs) {
        if (definitions.isEmpty()) return List.of();
        // CPM density is particles per logical game tick, not particles per
        // second. JavaCpmPoseSession calls us once for each logical tick.
        double now = age;
        double dt = Double.isFinite(previous) ? now - previous : 0;
        if (!Double.isFinite(dt) || dt < 0 || dt > .5) dt = 0;
        previous = now;
        var result = new ArrayList<CpmFeatures.Spawn>();
        for (var definition : definitions) {
            var source = definition.source();
            String particle = text(source, "particle", text(source, "effect", text(source, "type", "")));
            // CustomPlayerModel's legacy emitter is texture-backed rather than
            // a Minecraft particle id. Keep the texture symbol in the event so
            // a host with a CPM particle renderer can consume it; vanilla hosts
            // may intentionally drop it during registry lookup.
            if (particle.isBlank()) particle = text(source, "texture", "");
            if (particle.isBlank()) continue;
            double density = number(source, "density", 0, inputs);
            double rate = number(source, "rate", density, inputs);
            if (rate <= 0) continue;
            double clock = clocks.getOrDefault(definition.name(), 0d) + dt * rate;
            int count = Math.min(32, (int)Math.floor(clock));
            if (count > 0) clock -= count;
            clocks.put(definition.name(), clock);
            for (int i = 0; i < count; i++) {
                var position = vector(source.fields().get("position"), source.fields().get("pos"), inputs);
                if (source.fields().get("position") == null && source.fields().get("pos") == null)
                    position = randomVector(source.fields().get("posRange"), inputs);
                var velocity = vector(source.fields().get("velocity"), source.fields().get("direction"), inputs);
                double speed = randomRange(source, "speed", 0, inputs);
                int lifetime = (int)Math.round(range(source, "lifetime", range(source, "lifeSpan", 20, inputs), inputs));
                result.add(new CpmFeatures.Spawn(particle, definition.parent(), position, velocity, speed, lifetime, 1));
            }
        }
        return List.copyOf(result);
    }

    private static double[] vector(CpmValue primary, CpmValue fallback, Map<String, Double> inputs) {
        CpmValue value = primary == null ? fallback : primary;
        if (!(value instanceof CpmValue.ArrayValue array)) return new double[]{0, 0, 0};
        var result = new double[]{0, 0, 0};
        for (int i = 0; i < Math.min(3, array.values().size()); i++) result[i] = number(array.values().get(i), 0, inputs);
        return result;
    }
    private static double number(CpmValue.ObjectValue object, String key, double fallback, Map<String, Double> inputs) {
        return number(object.fields().get(key), fallback, inputs);
    }
    private static double number(CpmValue value, double fallback, Map<String, Double> inputs) {
        if (value instanceof CpmValue.NumberValue n) try { return Double.parseDouble(n.value()); } catch (RuntimeException ignored) {}
        if (value instanceof CpmValue.BooleanValue b) return b.value() ? 1 : 0;
        if (value instanceof CpmValue.StringValue s) try { return CpmExpressionEvaluator.evaluate(s.value(), inputs); } catch (RuntimeException ignored) {}
        if (value instanceof CpmValue.ArrayValue a && !a.values().isEmpty()) return number(a.values().get(0), fallback, inputs);
        return fallback;
    }
    private static String text(CpmValue.ObjectValue object, String key, String fallback) {
        var value = object.fields().get(key); return value instanceof CpmValue.StringValue s ? s.value() : fallback;
    }
    private static double range(CpmValue.ObjectValue object, String key, double fallback, Map<String, Double> inputs) {
        var value = object.fields().get(key);
        if (value instanceof CpmValue.ArrayValue array && !array.values().isEmpty()) {
            double a = number(array.values().get(0), fallback, inputs);
            double b = array.values().size() > 1 ? number(array.values().get(1), a, inputs) : a;
            return (a + b) * .5;
        }
        return number(value, fallback, inputs);
    }
    private double randomRange(CpmValue.ObjectValue object, String key, double fallback, Map<String, Double> inputs) {
        var value = object.fields().get(key);
        if (value instanceof CpmValue.ArrayValue array && !array.values().isEmpty()) {
            double a=number(array.values().get(0),fallback,inputs), b=array.values().size()>1?number(array.values().get(1),a,inputs):a;
            return a+(b-a)*random.nextDouble();
        }
        return number(value,fallback,inputs);
    }
    private double[] randomVector(CpmValue value, Map<String,Double> inputs) {
        if (!(value instanceof CpmValue.ArrayValue array)) return new double[]{0,0,0};
        var out=new double[]{0,0,0};
        for(int i=0;i<Math.min(3,array.values().size());i++) {
            var item=array.values().get(i);
            if(item instanceof CpmValue.ArrayValue range && !range.values().isEmpty()) {
                double a=number(range.values().get(0),0,inputs), b=range.values().size()>1?number(range.values().get(1),a,inputs):a;
                out[i]=a+(b-a)*random.nextDouble();
            } else out[i]=number(item,0,inputs);
        }
        return out;
    }
}
