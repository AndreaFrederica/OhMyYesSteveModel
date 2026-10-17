package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import java.util.*;

/** Evaluates the legacy bone presentation fields into a YSM-compatible pose snapshot. */
public final class CpmLegacyPoseEvaluator {
    private CpmLegacyPoseEvaluator() {}
    public record BonePose(double[] position,double[] rotation,double[] scale,boolean visible,
                           double[] color,double alpha,boolean emissive) {
        public BonePose { position=position.clone();rotation=rotation.clone();scale=scale.clone();color=color.clone(); }
    }
    public static Map<String,BonePose> evaluate(CpmDocument.Legacy source, Map<String,Double> inputs) {
        return evaluate(source, inputs, null);
    }

    /**
     * Evaluates the legacy CustomModel local position channel. The JSON
     * position is a ModelRenderer-local transform and must remain in the
     * runtime pose; the static compiler only converts the box vertex frame.
     */
    public static Map<String,BonePose> evaluate(CpmDocument.Legacy source, Map<String,Double> inputs,
                                                  Map<String,double[]> authoredPositions) {
        // Kept as a source-compatible overload for callers from the initial
        // adapter prototype. Legacy positions are local ModelRenderer
        // transforms, so an authored-position map must never be subtracted.
        // The argument is intentionally ignored.
        return evaluateInternal(source, inputs);
    }
    private static Map<String,BonePose> evaluateInternal(CpmDocument.Legacy source, Map<String,Double> inputs) {
        var variables=new LinkedHashMap<String,Double>();if(inputs!=null)variables.putAll(inputs);
        var output=new LinkedHashMap<String,BonePose>();
        for(var bone:source.bones()) {
            var o=bone.source();
            var position=vector(o.fields().get("position"),new double[]{0,0,0},variables);
            // Legacy CustomModel keeps position as a local ModelRenderer
            // translation.  The static compiler leaves custom bone pivots at
            // zero, so the complete evaluated position must reach the
            // renderer on every frame (including the authored rest value).
            var rotation=vector(o.fields().get("rotation"),new double[]{0,0,0},variables);
            var scale=vector(o.fields().get("scale"),new double[]{1,1,1},variables);
            var color=vector(o.fields().get("color"),new double[]{1,1,1},variables);
            boolean visible=bool(o.fields().get("visible"),true,variables);
            boolean emissive=bool(o.fields().get("emissive"),false,variables);
            double alpha=number(o.fields().get("alpha"),1,variables);
            output.put(bone.id(),new BonePose(position,rotation,scale,visible,color,alpha,emissive));
        }
        // CustomModel's skeleton section drives the vanilla ModelRenderer
        // rotation points every frame.  Expose the same local deltas for the
        // canonical host bones so sneak/carry animations move imported CPM
        // roots together with the player rig.
        var skeleton = source.source().fields().get("skeleton");
        if (skeleton instanceof CpmValue.ObjectValue object) {
            /* CustomModel's *_all aliases contain independent vanilla
             * ModelRenderers. They must not be applied to every node in the
             * synthetic YSM grouping chain or one point override is added
             * several times through the hierarchy. */
            var groups = Map.of(
                    "head_all", List.of("Head", "HeadOverlay"),
                    "body_all", List.of("UpperBody", "BodyOverlay"),
                    "right_arm_all", List.of("RightArm", "RightArmOverlay"),
                    "left_arm_all", List.of("LeftArm", "LeftArmOverlay"),
                    "right_leg_all", List.of("RightLeg", "RightLegOverlay"),
                    "left_leg_all", List.of("LeftLeg", "LeftLegOverlay"));
            for (var entry : groups.entrySet()) {
                var value = object.fields().get(entry.getKey());
                if (!(value instanceof CpmValue.ArrayValue array) || array.values().size() < 3) continue;
                /*
                 * CustomModel's updateSkeleton() writes these values directly
                 * to ModelRenderer.setRotationPoint(-x, -y, z).  They are
                 * absolute renderer points, not an AnimatedGeoBone delta.
                 * JavaModelState keeps the vanilla point in the baked host
                 * pivot and consumes the following encoded correction:
                 *
                 *   target YSM delta = (x + defaultX, y + defaultY,
                 *                       z - defaultZ)
                 * JavaModelState consumes attributes as
                 * (-attrX, +attrY, +attrZ), so encode the inverse X sign.
                 */
                var point = vector(value, new double[]{0, 0, 0}, variables);
                for (var name : entry.getValue()) {
                    var defaults = defaultRendererPoint(entry.getKey(), name);
                    // JavaModelState host branch consumes the attribute as
                    // T(pivot.x-attrX, pivot.y+attrY-24, pivot.z+attrZ).
                    // updateSkeleton writes the source point (-x,-y,z), so
                    // the exact encoded correction is [defaultX+x,
                    // defaultY+y, z-defaultZ].
                    var position = new double[]{
                            defaults[0] + point[0],
                            -(defaults[1] + point[1]),
                            point[2] - defaults[2]};
                    output.put(name, new BonePose(position, new double[]{0, 0, 0}, new double[]{1, 1, 1}, true,
                                new double[]{1, 1, 1}, 1, false));
                }
            }
        }
        return Map.copyOf(output);
    }

    /** Vanilla 1.12 ModelPlayer rotation points before CPM skeleton overrides. */
    private static double[] defaultRendererPoint(String group, String bone) {
        return switch (group) {
            case "left_arm_all" -> new double[]{5, 2, 0};
            case "right_arm_all" -> new double[]{-5, 2, bone.equals("RightArmOverlay") ? 10 : 0};
            case "left_leg_all" -> new double[]{1.9, 12, 0};
            case "right_leg_all" -> new double[]{-1.9, 12, 0};
            default -> new double[]{0, 0, 0};
        };
    }
    private static double[] vector(CpmValue value,double[] fallback,Map<String,Double> vars){
        if(!(value instanceof CpmValue.ArrayValue array))return fallback.clone();var out=fallback.clone();
        for(int i=0;i<Math.min(out.length,array.values().size());i++)out[i]=number(array.values().get(i),out[i],vars);return out;
    }
    private static double number(CpmValue value,double fallback,Map<String,Double> vars){
        if(value instanceof CpmValue.NumberValue n)return Double.parseDouble(n.value());
        if(value instanceof CpmValue.BooleanValue b)return b.value()?1:0;
        if(value instanceof CpmValue.StringValue s){try{return CpmExpressionEvaluator.evaluate(s.value(),vars);}catch(RuntimeException ignored){return fallback;}}
        return fallback;
    }
    private static boolean bool(CpmValue value,boolean fallback,Map<String,Double> vars){
        if(value instanceof CpmValue.BooleanValue b)return b.value();
        if(value instanceof CpmValue.StringValue s){try{return CpmExpressionEvaluator.evaluateBoolean(s.value(),vars);}catch(RuntimeException ignored){return fallback;}}
        return value==null?fallback:number(value,fallback?1:0,vars)!=0;
    }
}
