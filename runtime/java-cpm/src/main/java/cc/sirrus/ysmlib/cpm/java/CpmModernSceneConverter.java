package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import java.util.*;

/**
 * Modern CustomPlayerModel 1.16 canonical compiler.
 *
 * This class intentionally has no Legacy bone, box, or ModelRenderer code.
 */
final class CpmModernSceneConverter {
    private static final Set<String> CANONICAL = Set.of("Root", "MAllBody", "AllBody", "UpBody", "UpperBody", "Arm",
            "LeftArm", "LeftForeArm", "LeftHand", "RightArm", "RightForeArm", "RightHand", "AllHead", "MHead", "Head",
            "DownBody", "Leg", "LeftLeg", "LeftLowerLeg", "LeftFoot", "RightLeg", "RightLowerLeg", "RightFoot",
            "HeadOverlay", "BodyOverlay", "LeftArmOverlay", "RightArmOverlay", "LeftLegOverlay", "RightLegOverlay");
    private CpmModernSceneConverter() {}
    static CpmCompiledModel compile(CpmDocument.Remake source) {
        return new ModernCompiler(source).compileModernOnly();
    }

    private static final class ModernCompiler {
        private final CpmDocument.Remake source;
        private final List<Spec> specs = new ArrayList<>();
        private final Map<String, Integer> specIndex = new LinkedHashMap<>();
        private final List<PendingPrimitive> pending = new ArrayList<>();
        private final Map<String, CpmCompiledModel.Texture> textures = new LinkedHashMap<>();
        private final List<CpmCompiledModel.Locator> locators = new ArrayList<>();
        private Map<String, String> sourceRename = Map.of();
        private int textureWidth = 64, textureHeight = 64;
        private double defaultScale = 1d;
        private ModernCompiler(CpmDocument.Remake source) { this.source = source; }




        private CpmCompiledModel compileModernOnly() {
            compileRemake(source); addLocators(); return compileResult();
        }
        private CpmCompiledModel compileResult() {
            var bones = new ArrayList<CpmCompiledModel.Bone>(specs.size());
            for (int i = 0; i < specs.size(); i++) { var spec = specs.get(i); int parent = spec.parent == null || spec.parent.isEmpty() ? -1 : specIndex.getOrDefault(spec.parent, -1); bones.add(new CpmCompiledModel.Bone(i, spec.name, parent, spec.pivot, spec.rotation)); }
            var primitives = new ArrayList<CpmCompiledModel.Primitive>(pending.size());
            for (var item : pending) { int bone = specIndex.getOrDefault(item.bone, -1); if (bone < 0) throw new IllegalStateException("CPM primitive references missing bone: " + item.bone); primitives.add(new CpmCompiledModel.Primitive(item.kind, bone, item.origin, item.size, item.pivot, item.rotation, item.authored, item.textureOffset, item.inflate, item.mirror, item.uv, item.texture, item.sourceObject)); }
            return new CpmCompiledModel(bones, primitives, textures, source, textureWidth, textureHeight, defaultScale, locators);
        }







        private void compileRemake(CpmDocument.Remake remake) {
            defaultScale = number(remake.source().fields().get("default_scale"), 1d);
            var sourceNames = new LinkedHashSet<String>();
            for (var part : remake.parts()) for (var model : part.geometry().models())
                for (var bone : model.bones()) sourceNames.add(bone.name());
            var renamed = new LinkedHashMap<String, String>();
            for (var name : sourceNames) {
                String mapped = CANONICAL.contains(name) ? "__cpm_" + name : name;
                while (!mapped.equals(name) && sourceNames.contains(mapped)) mapped = "_" + mapped;
                renamed.put(name, mapped);
            }
            sourceRename = renamed;
            addHostSkeleton(remake.skeleton().type(), new LinkedHashSet<>(renamed.values()), remake.skeleton().parameters());
            for (var part : remake.parts()) for (var model : part.geometry().models()) {
                textureWidth = model.textureWidth(); textureHeight = model.textureHeight();
                for (var srcBone : model.bones()) {
                    String emitted = renamed.getOrDefault(srcBone.name(), srcBone.name());
                    String parent = srcBone.parent();
                    if (parent == null || parent.isEmpty() || "none".equals(parent)) {
                        // CustomPlayerModel's ModelBone.instantiate gives a
                        // root named *_c one special resolution: it strips
                        // the suffix and asks ModelBase.getBone() for that
                        // name.  ModelBase resolves authored model bones
                        // before the vanilla skeleton. Preserve that exact
                        // namespace rule before considering equipment
                        // attachments or heuristic names.
                        String rootBase = emitted.endsWith("_c") ? emitted.substring(0, emitted.length() - 2) : null;
                        if (rootBase != null && sourceNames.contains(rootBase)) parent = renamed.getOrDefault(rootBase, rootBase);
                        else if (rootBase != null && hostBones(remake.skeleton().type()).contains(rootBase)) parent = rootBase;
                        else if (rootBase != null && CANONICAL.contains(canonicalHost(rootBase))) parent = canonicalHost(rootBase);
                        else {
                            // ModelBone.instantiate only performs the
                            // skeleton lookup for the explicit ``*_c`` root
                            // convention.  A normal root (for example
                            // body_sword) is resolved through parentName
                            // ``none`` and remains parentless; attachment
                            // metadata is a locator declaration, not a bone
                            // parent heuristic.
                            parent = null;
                        }
                    }
                    if (parent != null && !parent.isEmpty()) {
                        // A source bone name has precedence over the vanilla
                        // skeleton namespace.  CustomPlayerModel resolves
                        // model bones before skeleton bones, so a lowercase
                        // authored "body" is a real custom parent rather
                        // than the canonical UpperBody host.  Apply the host
                        // alias only when the parent is not an authored
                        // source name.
                        String authoredParent = renamed.get(parent);
                        parent = authoredParent != null ? authoredParent : canonicalHost(parent);
                    }
                    addSpec(emitted, parent, vec(srcBone.pivot()), vec(srcBone.rotation()));
                    for (var cube : srcBone.cubes()) {
                        var uv = cube.uv() == null ? CpmValue.NullValue.INSTANCE : cube.uv();
                        addPrimitive(CpmCompiledModel.Kind.CUBE, emitted, vec(cube.origin()), vec(cube.size()),
                                vec(cube.pivot()), vec(cube.rotation()), List.of(), List.of(), cube.inflate(),
                                cube.mirror(), uv, part.texture(), cube.source());
                    }
                }
                if (!part.texture().isEmpty()) textures.putIfAbsent(part.texture(),
                        new CpmCompiledModel.Texture(part.texture(), part.packageTexturePath()));
            }
        }

        private void addHostSkeleton(String type, Set<String> sourceNames, Map<String, Double> parameters) {
            addHostSkeleton(type, sourceNames, parameters, hostPivots(type, parameters));
        }

        private void addHostSkeleton(String type, Set<String> sourceNames, Map<String, Double> parameters,
                                     Map<String, CpmCompiledModel.Vec3> pivots) {
            if (!"biped".equals(type)) {
                for (var host : hostBones(type)) if (!sourceNames.contains(host)) addSpec(host, null, CpmCompiledModel.Vec3.ZERO, CpmCompiledModel.Vec3.ZERO);
                return;
            }
            var p = pivots;
            var hosts = new ArrayList<>(List.of(new Host("Root", null), new Host("MAllBody", "Root"), new Host("AllBody", "MAllBody"),
                    new Host("UpBody", "AllBody"), new Host("UpperBody", "UpBody"), new Host("Arm", "UpperBody"),
                    new Host("LeftArm", "Arm"), new Host("LeftForeArm", "LeftArm"), new Host("LeftHand", "LeftForeArm"),
                    new Host("RightArm", "Arm"), new Host("RightForeArm", "RightArm"), new Host("RightHand", "RightForeArm"),
                    new Host("AllHead", "UpperBody"), new Host("MHead", "AllHead"), new Host("Head", "MHead"),
                    new Host("DownBody", "AllBody"), new Host("Leg", "DownBody"), new Host("LeftLeg", "Leg"),
                    new Host("LeftLowerLeg", "LeftLeg"), new Host("LeftFoot", "LeftLowerLeg"), new Host("RightLeg", "Leg"),
                    new Host("RightLowerLeg", "RightLeg"), new Host("RightFoot", "RightLowerLeg")));
            for (var host : hosts) if (!sourceNames.contains(host.name))
                addSpec(host.name, host.parent, p.getOrDefault(host.name, CpmCompiledModel.Vec3.ZERO), CpmCompiledModel.Vec3.ZERO);
        }



        private void addLocators() {
            CpmDocument.Remake remake = source;
            for (var binding : CpmAttachments.collect(remake))
                locators.add(new CpmCompiledModel.Locator(sourceRename.getOrDefault(binding.bone(), binding.bone()), binding.slot()));
            remake.skeleton().leftArm().ifPresent(list -> list.forEach(n -> locators.add(new CpmCompiledModel.Locator(sourceRename.getOrDefault(n, n), "first_person_left"))));
            remake.skeleton().rightArm().ifPresent(list -> list.forEach(n -> locators.add(new CpmCompiledModel.Locator(sourceRename.getOrDefault(n, n), "first_person_right"))));
            if (remake.skeleton().leftArm().isEmpty()) addDefaultLocators("left_arm", "first_person_left");
            if (remake.skeleton().rightArm().isEmpty()) addDefaultLocators("right_arm", "first_person_right");
            for (var spec : specs) if (spec.name.endsWith("_c") && "Head".equals(spec.parent))
                locators.add(new CpmCompiledModel.Locator(spec.name, "helmet"));
        }

        private void addDefaultLocators(String host, String group) {
            for (var spec : specs) {
                String current = spec.parent; var seen = new HashSet<String>();
                while (current != null && seen.add(current)) {
                    if (host.equals(current) || canonicalHost(host).equals(current)) { locators.add(new CpmCompiledModel.Locator(spec.name, group)); break; }
                    int index = specIndex.getOrDefault(current, -1); current = index < 0 ? null : specs.get(index).parent;
                }
            }
        }


        private void addSpec(String name, String parent, CpmCompiledModel.Vec3 pivot, CpmCompiledModel.Vec3 rotation) {
            if (name == null || name.isEmpty() || specIndex.containsKey(name)) throw new IllegalArgumentException("Duplicate CPM bone: " + name);
            specIndex.put(name, specs.size()); specs.add(new Spec(name, parent, pivot, rotation));
        }
        private void addPrimitive(CpmCompiledModel.Kind kind, String bone, CpmCompiledModel.Vec3 origin,
                                  CpmCompiledModel.Vec3 size, CpmCompiledModel.Vec3 pivot, CpmCompiledModel.Vec3 rotation,
                                  List<Double> authored, List<Double> textureOffset, double inflate, boolean mirror,
                                  CpmValue uv, String texture, Object sourceObject) {
            pending.add(new PendingPrimitive(kind, bone, origin, size, pivot, rotation, authored, textureOffset,
                    inflate, mirror, uv, texture, sourceObject));
        }
        private record Spec(String name, String parent, CpmCompiledModel.Vec3 pivot, CpmCompiledModel.Vec3 rotation) {}
        private record PendingPrimitive(CpmCompiledModel.Kind kind, String bone, CpmCompiledModel.Vec3 origin,
                                        CpmCompiledModel.Vec3 size, CpmCompiledModel.Vec3 pivot, CpmCompiledModel.Vec3 rotation,
                                        List<Double> authored, List<Double> textureOffset, double inflate, boolean mirror,
                                        CpmValue uv, String texture, Object sourceObject) {}
        private record Host(String name, String parent) {}
    }

    private static List<String> hostBones(String type) {
        if ("quadruped".equals(type)) return List.of("body", "head", "left_front_leg", "right_front_leg", "left_back_leg", "right_back_leg");
        if ("slime".equals(type)) return List.of("body");
        return List.of("head", "body", "left_arm", "right_arm", "left_leg", "right_leg");
    }
    private static double parameter(Map<String, Double> parameters, String key, double fallback) {
        var value = parameters.get(key);
        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    private static Map<String, CpmCompiledModel.Vec3> hostPivots(String type, Map<String, Double> parameters) {
        var p = new LinkedHashMap<String, CpmCompiledModel.Vec3>(); p.put("Root", CpmCompiledModel.Vec3.ZERO);
            if ("slime".equals(type)) { p.put("body", new CpmCompiledModel.Vec3(0, parameter(parameters, "body_pivot_height", 24), parameter(parameters, "body_offset", 0))); return p; }
            if ("quadruped".equals(type)) {
                double legY = parameter(parameters, "leg_length", 6), legX = parameter(parameters, "leg_interval", 6) / 2d;
                double legZ = parameter(parameters, "leg_offset", 1), frontZ = legZ + parameter(parameters, "body_length", 12);
                p.put("head", new CpmCompiledModel.Vec3(0, parameter(parameters, "head_pivot_height", 12), parameter(parameters, "head_offset", -6)));
                p.put("body", new CpmCompiledModel.Vec3(0, parameter(parameters, "body_pivot_height", 11), parameter(parameters, "body_offset", 2)));
                p.put("left_back_leg", new CpmCompiledModel.Vec3(legX, legY, legZ)); p.put("right_back_leg", new CpmCompiledModel.Vec3(-legX, legY, legZ));
                p.put("left_front_leg", new CpmCompiledModel.Vec3(legX, legY, frontZ)); p.put("right_front_leg", new CpmCompiledModel.Vec3(-legX, legY, frontZ));
                return p;
            }
            if (!"biped".equals(type) && !"zombie".equals(type)) { for (var host : hostBones(type)) p.put(host, CpmCompiledModel.Vec3.ZERO); return p; }
        // The canonical host branch renders a static host point as
        // (pivot.x, pivot.y - 24, pivot.z) after GeoBuilder reflects pivot X.
        // Choose the IR pivot so that this is exactly the original 1.12
        // ModelRenderer rotation point (raw.x, raw.y, raw.z).
        double bodyY = parameter(parameters, "body_pivot_height", 24), bodyZ = parameter(parameters, "body_offset", 0);
        double headY = parameter(parameters, "head_pivot_height", 24), headZ = parameter(parameters, "head_offset", 0);
        double armY = parameter(parameters, "arm_pivot_height", 22), armZ = parameter(parameters, "arm_offset", 0), armX = parameter(parameters, "arm_interval", 10) / 2d;
        double legY = parameter(parameters, "leg_length", 12), legZ = parameter(parameters, "leg_offset", 0), legX = parameter(parameters, "leg_interval", 3.8) / 2d;
        var body = new CpmCompiledModel.Vec3(0, bodyY, bodyZ); var arm = new CpmCompiledModel.Vec3(0, armY, armZ);
        var leftArm = new CpmCompiledModel.Vec3(armX, armY, armZ); var rightArm = new CpmCompiledModel.Vec3(-armX, armY, armZ);
        var head = new CpmCompiledModel.Vec3(0, headY, headZ); var leg = new CpmCompiledModel.Vec3(0, legY, legZ);
        var leftLeg = new CpmCompiledModel.Vec3(legX, legY, legZ); var rightLeg = new CpmCompiledModel.Vec3(-legX, legY, legZ);
        for (var n : List.of("MAllBody", "AllBody", "UpBody", "UpperBody", "DownBody")) p.put(n, body);
        p.put("Arm", arm); for (var n : List.of("LeftArm", "LeftForeArm", "LeftHand")) p.put(n, leftArm); for (var n : List.of("RightArm", "RightForeArm", "RightHand")) p.put(n, rightArm);
        for (var n : List.of("AllHead", "MHead", "Head")) p.put(n, head); p.put("Leg", leg);
        for (var n : List.of("LeftLeg", "LeftLowerLeg", "LeftFoot")) p.put(n, leftLeg); for (var n : List.of("RightLeg", "RightLowerLeg", "RightFoot")) p.put(n, rightLeg);
        return p;
    }
    private static double number(CpmValue value, double fallback) {
        if (value instanceof CpmValue.NumberValue n) try { return Double.parseDouble(n.value()); } catch (NumberFormatException ignored) { }
        // Constant-valued source fields may use a simple expression
        // (for example "1+2").  It is safe to fold expressions that do not
        // reference runtime variables; variable expressions intentionally
        // retain the fallback and are evaluated by the pose session.
        if (value instanceof CpmValue.StringValue s) {
            try { return CpmExpressionEvaluator.evaluate(s.value(), Map.of()); }
            catch (RuntimeException ignored) { }
        }

        return fallback;
    }
    private static CpmCompiledModel.Vec3 vec(List<Double> values, int offset) { return new CpmCompiledModel.Vec3(values.get(offset), values.get(offset + 1), values.get(offset + 2)); }
    private static CpmCompiledModel.Vec3 vec(CpmGeometry.Vector value) { return new CpmCompiledModel.Vec3(value.x(), value.y(), value.z()); }
    private static CpmValue uv(List<Double> values) { return new CpmValue.ArrayValue(values.stream().map(v -> (CpmValue) new CpmValue.NumberValue(Double.toString(v))).toList()); }
    private static String canonicalHost(String name) {
        return switch (name) { case "head" -> "Head"; case "head_overlay" -> "HeadOverlay"; case "body" -> "UpperBody"; case "body_overlay" -> "BodyOverlay"; case "left_arm" -> "LeftArm"; case "left_arm_overlay" -> "LeftArmOverlay"; case "right_arm" -> "RightArm"; case "right_arm_overlay" -> "RightArmOverlay"; case "left_leg" -> "LeftLeg"; case "left_leg_overlay" -> "LeftLegOverlay"; case "right_leg" -> "RightLeg"; case "right_leg_overlay" -> "RightLegOverlay"; default -> name; };
    }
    private static String remakeRootAttachment(CpmDocument.Remake source, CpmGeometry.Bone bone) {
        String name = bone.name(), slot = source.addons().get(name);
        if (slot == null) for (var entry : source.skeleton().attachments().entrySet()) if (entry.getValue().contains(name)) { slot = entry.getKey(); break; }
        if (slot == null && source.skeleton().leftArm().map(v -> v.contains(name)).orElse(false)) slot = "left_arm";
        if (slot == null && source.skeleton().rightArm().map(v -> v.contains(name)).orElse(false)) slot = "right_arm";
        if (slot == null) { String lower = name.toLowerCase(Locale.ROOT); if (lower.matches(".*(head|helmet|face|hair).*")) slot = "head"; else if (lower.matches(".*(left|l_).*(arm|hand).*")) slot = "left_arm"; else if (lower.matches(".*(right|r_).*(arm|hand).*")) slot = "right_arm"; else if (lower.matches(".*(left|l_).*(leg|foot).*")) slot = "left_leg"; else if (lower.matches(".*(right|r_).*(leg|foot).*")) slot = "right_leg"; else if (lower.matches(".*(body|chest|torso|cape|wing).*")) slot = "body"; }
        return switch (slot == null ? "" : slot) {
            case "helmet", "skull", "head" -> "Head";
            case "chestplate_body", "cape", "elytra", "body", "leggings_body" -> "UpperBody";
            case "left_arm", "chestplate_left", "item_left", "parrot_left" -> "LeftArm";
            case "right_arm", "chestplate_right", "item_right", "parrot_right" -> "RightArm";
            case "left_leg", "leggings_left", "boots_left" -> "LeftLeg";
            case "right_leg", "leggings_right", "boots_right" -> "RightLeg";
            default -> null;
        };
    }
}
