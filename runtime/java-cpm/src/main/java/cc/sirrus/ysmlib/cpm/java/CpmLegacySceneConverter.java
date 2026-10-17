package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import java.util.*;

/**
 * Legacy CustomModel 1.12 canonical compiler.
 *
 * This class intentionally has no Modern geometry or skeleton code.
 */
final class CpmLegacySceneConverter {
    private CpmLegacySceneConverter() {}
    static CpmCompiledModel compile(CpmDocument.Legacy source) {
        return new LegacyCompiler(source).compileLegacyOnly();
    }

    private static final class LegacyCompiler {
        private final CpmDocument.Legacy source;
        private final List<Spec> specs = new ArrayList<>();
        private final Map<String, Integer> specIndex = new LinkedHashMap<>();
        private final List<PendingPrimitive> pending = new ArrayList<>();
        private final Map<String, CpmCompiledModel.Texture> textures = new LinkedHashMap<>();
        private final List<CpmCompiledModel.Locator> locators = new ArrayList<>();
        private Map<String, String> sourceRename = Map.of();
        private int textureWidth = 64, textureHeight = 64;
        private double defaultScale = 1d;
        private LegacyCompiler(CpmDocument.Legacy source) { this.source = source; }



        private CpmCompiledModel compileLegacyOnly() {
            compileLegacy(source); addLocators(); return compileResult();
        }

        private CpmCompiledModel compileResult() {
            var bones = new ArrayList<CpmCompiledModel.Bone>(specs.size());
            for (int i = 0; i < specs.size(); i++) { var spec = specs.get(i); int parent = spec.parent == null || spec.parent.isEmpty() ? -1 : specIndex.getOrDefault(spec.parent, -1); bones.add(new CpmCompiledModel.Bone(i, spec.name, parent, spec.pivot, spec.rotation)); }
            var primitives = new ArrayList<CpmCompiledModel.Primitive>(pending.size());
            for (var item : pending) { int bone = specIndex.getOrDefault(item.bone, -1); if (bone < 0) throw new IllegalStateException("CPM primitive references missing bone: " + item.bone); primitives.add(new CpmCompiledModel.Primitive(item.kind, bone, item.origin, item.size, item.pivot, item.rotation, item.authored, item.textureOffset, item.inflate, item.mirror, item.uv, item.texture, item.sourceObject)); }
            return new CpmCompiledModel(bones, primitives, textures, source, textureWidth, textureHeight, defaultScale, locators);
        }

        private void compileLegacy(CpmDocument.Legacy legacy) {
            // Legacy CustomModel lets each bone declare textureSize.  The
            // YSM bake payload has one atlas dimension for all cubes, so use
            // the largest explicit source atlas dimension as the canonical
            // normalization.  Archived CPM packs (including Marisa) put the
            // real 256x256 atlas on the root bone while child bones omit the
            // field and inherit the same texture resource at render time.
            for (var bone : legacy.bones()) {
                var value = bone.source().fields().get("textureSize");
                if (value instanceof CpmValue.ArrayValue array && array.values().size() >= 2) {
                    textureWidth = Math.max(textureWidth, (int) Math.round(number(array.values().get(0), textureWidth)));
                    textureHeight = Math.max(textureHeight, (int) Math.round(number(array.values().get(1), textureHeight)));
                }
            }
            var pivots = legacyHostPivots(legacy);
            // Legacy CustomModel is a 1.12 ModelRenderer graph.  Every
            // PlayerBone is an independent renderer (OriginalBone.getParent()
            // returns null), while a custom bone's position is a local
            // renderer translation.  Convert that graph to YSM's reflected
            // model frame once at bake time: boxes become absolute geometry
            // and the runtime only applies animated deltas around absolute
            // pivots.  This is what keeps arm/leg roots attached to their
            // vanilla rotation points instead of leaving them at the origin.
            var absolute = new LinkedHashMap<String, CpmCompiledModel.Vec3>();
            // CustomModel's PlayerBone.OriginalBone instances are not a
            // Minecraft ModelRenderer child graph: getParent() returns null
            // for every slot.  Keep the host slots independent in the
            // canonical Legacy representation as well.  A synthetic
            // Root->Body->Arm hierarchy repeats rotation points (5,2,0 and
            // 12,0,0, etc.) once per grouping node and makes old models fan
            // out or become many times too large.  Custom bones still retain
            // their authored parent chain and can attach directly to a host
            // slot by name.
            addLegacyHostSkeleton(pivots);
            for (var bone : legacy.bones()) {
                String parent = bone.parentIndex() < 0
                        ? ("none".equals(bone.parent()) ? null : canonicalHost(bone.parent()))
                        : legacy.bones().get(bone.parentIndex()).id();
                var local = legacyPosition(bone.source());
                var hostPivots = legacyHostModelPivots(legacy);
                var parentPoint = bone.parentIndex() >= 0
                        ? absolute.getOrDefault(legacy.bones().get(bone.parentIndex()).id(), CpmCompiledModel.Vec3.ZERO)
                        : hostPivots.getOrDefault(canonicalHost(bone.parent()), CpmCompiledModel.Vec3.ZERO);
                var point = new CpmCompiledModel.Vec3(parentPoint.x() + local.x(),
                        parentPoint.y() + local.y(), parentPoint.z() + local.z());
                absolute.put(bone.id(), point);
                /*
                 * A CustomModel bone is a local ModelRenderer transform.  Its
                 * authored position is consumed by Bone.getTransform() as
                 * R * T(position); it is not an absolute YSM pivot.  Keep the
                 * compact runtime pivot at zero so JavaModelState selects the
                 * Legacy local-transform branch.  Host PlayerBone instances
                 * retain their absolute pivots above this custom hierarchy.
                 */
                addSpec(bone.id(), parent, CpmCompiledModel.Vec3.ZERO,
                        CpmCompiledModel.Vec3.ZERO);
                // CustomModel.Bone.getTexture() inherits the nearest parent
                // texture and ultimately falls back to ModelPack's base
                // texture.  Resolving that chain at conversion time keeps
                // child bones with omitted `texture` fields on the same
                // texture as the original renderer.
                String texture = legacyTexture(legacy, bone);
                // Legacy ModelPack resolves tex.<symbol> against the package
                // texture table before binding the bone.  The canonical
                // compiler used to leave this map empty, which made the
                // runtime silently fall back to an unrelated host texture
                // and made offline previews unable to sample the authored
                // atlas.  Preserve the symbol and resolve the package path
                // with the same stem/alias rules as the source reader.
                if (!texture.isBlank()) {
                    String path = texturePath(legacy, texture);
                    if (!path.isBlank()) textures.putIfAbsent(texture,
                            new CpmCompiledModel.Texture(texture, path));
                }
                for (var box : bone.boxes()) {
                    var c = box.coordinates();
                    if (c.size() != 6) throw new IllegalArgumentException("CPM box requires six coordinates");
                    // CustomModel.Box constructs local ModelRenderer
                    // vertices [-x-width,-x] / [-y-height,-y] /
                    // [z-depth,z]. GeoBuilder's cube baker emits
                    // [-origin.x-size.x,-origin.x] / [origin.y,origin.y+
                    // size.y] / [origin.z,origin.z+size.z], so encode the
                    // exact source minimum corner for all three channels.
                    // The authored bone position remains a runtime transform.
                    var origin = new CpmCompiledModel.Vec3(
                            c.get(0), -c.get(1) - c.get(4), c.get(2) - c.get(5));
                    addPrimitive(CpmCompiledModel.Kind.CUBE, bone.id(), origin, vec(c, 3),
                            CpmCompiledModel.Vec3.ZERO, CpmCompiledModel.Vec3.ZERO, c, box.textureOffset(),
                            box.sizeAdd(), box.mirror(), uv(box.textureOffset()), texture, box.source());
                }
                for (var quad : bone.quads()) {
                    var c = quad.coordinates();
                    if (c.size() != 5) throw new IllegalArgumentException("CPM quad requires five coordinates");
                    // Legacy quads use the same ModelRenderer frame as Box:
                    // coordinates are [x,y,z,width,height], with y/z
                    // measured from the maximum corner.
                    var origin = new CpmCompiledModel.Vec3(c.get(0), -c.get(1) - c.get(4), c.get(2));
                    var size = new CpmCompiledModel.Vec3(c.get(3), c.get(4), 0);
                    addPrimitive(CpmCompiledModel.Kind.QUAD, bone.id(), origin, size,
                            CpmCompiledModel.Vec3.ZERO, CpmCompiledModel.Vec3.ZERO, c, quad.textureOffset(),
                            quad.sizeAdd(), false, uv(quad.textureOffset()), texture, quad.source());
                }
            }
        }

        private String legacyTexture(CpmDocument.Legacy model, CpmDocument.LegacyBone bone) {
            var current = bone;
            var seen = new HashSet<String>();
            while (current != null && seen.add(current.id())) {
                String own = legacyOwnTexture(current.source());
                if (!own.isBlank()) return own;
                int parent = current.parentIndex();
                current = parent >= 0 && parent < model.bones().size() ? model.bones().get(parent) : null;
            }
            return model.bones().stream().map(b -> legacyOwnTexture(b.source()))
                    .filter(value -> !value.isBlank()).findFirst().orElse("");
        }

        private String legacyOwnTexture(CpmValue.ObjectValue source) {
            var value = source.fields().get("texture");
            if (value instanceof CpmValue.StringValue s) {
                String expression = s.value().trim();
                // A legacy texture expression can select by health/variables;
                // the static preview uses its first tex.<symbol> branch.
                int tex = expression.indexOf("tex.");
                if (tex >= 0) {
                    String tail = expression.substring(tex + 4);
                    int end = 0;
                    while (end < tail.length() && (Character.isLetterOrDigit(tail.charAt(end)) || tail.charAt(end) == '_' || tail.charAt(end) == '-')) end++;
                    if (end > 0) return "tex." + tail.substring(0, end);
                }
                return expression;
            }
            return "";
        }

        private String texturePath(CpmDocument.Legacy legacy, String symbol) {
            String key = symbol.startsWith("tex.") ? symbol.substring(4) : symbol;
            String exact = "";
            for (String path : legacy.files().keySet()) {
                String lower = path.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".bmp"))) continue;
                int slash = path.lastIndexOf('/');
                String leaf = slash < 0 ? path : path.substring(slash + 1);
                int dot = leaf.lastIndexOf('.');
                String stem = dot > 0 ? leaf.substring(0, dot) : leaf;
                if (stem.equalsIgnoreCase(key) || leaf.equalsIgnoreCase(key)) return path;
                if (stem.equalsIgnoreCase(symbol)) exact = path;
            }
            return exact;
        }

        private CpmCompiledModel.Vec3 legacyPosition(CpmValue.ObjectValue source) {
            var value = source.fields().get("position");
            if (!(value instanceof CpmValue.ArrayValue array) || array.values().size() < 3)
                return CpmCompiledModel.Vec3.ZERO;
            // Bone.update() applies (-x, -y, +z) in vanilla ModelRenderer
            // space.  This value is accumulated before the final YSM frame
            // projection, so keep that exact renderer translation here.
            return new CpmCompiledModel.Vec3(-number(array.values().get(0), 0),
                    -number(array.values().get(1), 0), number(array.values().get(2), 0));
        }







        private void addLegacyHostSkeleton(Map<String, CpmCompiledModel.Vec3> pivots) {
            var hosts = List.of(
                    "Head", "HeadOverlay", "UpperBody", "BodyOverlay",
                    "LeftArm", "LeftArmOverlay", "RightArm", "RightArmOverlay",
                    "LeftLeg", "LeftLegOverlay", "RightLeg", "RightLegOverlay");
            for (var host : hosts) {
                addSpec(host, null, pivots.getOrDefault(host, CpmCompiledModel.Vec3.ZERO),
                        CpmCompiledModel.Vec3.ZERO);
            }
        }

        private void addLocators() {
            CpmDocument.Legacy legacy = source;
            for (var key : List.of("fpLeft", "fpRight", "fpList")) {
                var group = key.equals("fpLeft") ? "first_person_left" : key.equals("fpRight") ? "first_person_right" : "first_person_background";
                var value = legacy.source().fields().get(key);
                if (value instanceof CpmValue.ArrayValue array) for (var item : array.values()) if (item instanceof CpmValue.StringValue text)
                    locators.add(new CpmCompiledModel.Locator(legacyReference(legacy, text.value()), group));
            }
            addDefaultLocators("left_arm", "first_person_left");
            addDefaultLocators("right_arm", "first_person_right");
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

        private String legacyReference(CpmDocument.Legacy legacy, String authored) {
            String result = authored;
            for (var bone : legacy.bones()) {
                var id = bone.source().fields().get("id");
                if (id instanceof CpmValue.StringValue text && text.value().equals(authored)) result = bone.id();
            }
            return result;
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

    private static Map<String, CpmCompiledModel.Vec3> legacyHostPivots(CpmDocument.Legacy source) {
        var pivots = new LinkedHashMap<String, CpmCompiledModel.Vec3>();
        // CustomModel 1.12 reads the live ModelPlayer rotation points.  Its
        // defaults are head/body=(0,0,0), arms=(+/-5,2,0), legs=(+/-1.9,12,0).
        // Keep the raw ModelRenderer point. GeoBuilder converts it to the
        // runtime frame as (-x, 24-y, z), exactly like CustomModel's player
        // render transform.
        legacyHostModelPivots(source).forEach((name, raw) -> pivots.put(name,
                new CpmCompiledModel.Vec3(-raw.x(), 24d + raw.y(), raw.z())));
        var skeleton = source.source().fields().get("skeleton");
        if (!(skeleton instanceof CpmValue.ObjectValue object)) return pivots;
        /*
         * The skeleton section is evaluated every render frame by
         * CustomModel.updateSkeleton().  It changes the live
         * ModelRenderer.rotationPoint; it must not be baked into the static
         * YSM pivot, otherwise an expression or a later frame cannot move the
         * attachment back to the vanilla point.  CpmLegacyPoseEvaluator
         * emits the corresponding runtime correction instead.
         */
        return pivots;
    }
    private static Map<String, CpmCompiledModel.Vec3> legacyHostModelPivots(CpmDocument.Legacy source) {
        var result = new LinkedHashMap<String, CpmCompiledModel.Vec3>();
        for (var name : List.of("Head", "AllHead", "MHead", "UpperBody", "UpBody", "AllBody", "MAllBody", "DownBody"))
            result.put(name, new CpmCompiledModel.Vec3(0, 0, 0));
        // Arm/Leg are YSM canonical grouping nodes.  CustomModel's
        // PlayerBone values are all independent ModelRenderers (their
        // getParent() returns null), so these grouping nodes must carry no
        // rotation point; the concrete Left/Right nodes below carry the
        // complete vanilla attachment point exactly once.
        result.put("Arm", new CpmCompiledModel.Vec3(0, 0, 0));
        for (var name : List.of("LeftArm", "LeftForeArm", "LeftHand")) result.put(name, new CpmCompiledModel.Vec3(5, 2, 0));
        for (var name : List.of("RightArm", "RightForeArm", "RightHand")) result.put(name, new CpmCompiledModel.Vec3(-5, 2, 0));
        result.put("LeftArmOverlay", new CpmCompiledModel.Vec3(5, 2, 0));
        result.put("RightArmOverlay", new CpmCompiledModel.Vec3(-5, 2, 10));
        result.put("Leg", new CpmCompiledModel.Vec3(0, 0, 0));
        for (var name : List.of("LeftLeg", "LeftLowerLeg", "LeftFoot")) result.put(name, new CpmCompiledModel.Vec3(1.9, 12, 0));
        for (var name : List.of("RightLeg", "RightLowerLeg", "RightFoot")) result.put(name, new CpmCompiledModel.Vec3(-1.9, 12, 0));
        result.put("LeftLegOverlay", new CpmCompiledModel.Vec3(1.9, 12, 0));
        result.put("RightLegOverlay", new CpmCompiledModel.Vec3(-1.9, 12, 0));
        result.put("HeadOverlay", new CpmCompiledModel.Vec3(0, 0, 0));
        result.put("BodyOverlay", new CpmCompiledModel.Vec3(0, 0, 0));
        return result;
    }
    private static double number(CpmValue value, double fallback) {
        if (value instanceof CpmValue.NumberValue n) try { return Double.parseDouble(n.value()); } catch (NumberFormatException ignored) { }
        // Legacy position/skeleton fields may use a constant expression
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
    private static String legacyHostRoot(CpmDocument.LegacyBone bone, List<CpmDocument.LegacyBone> all) {
        var current = bone; var seen = new HashSet<String>();
        while (current.parentIndex() >= 0 && current.parentIndex() < all.size() && seen.add(current.id())) current = all.get(current.parentIndex());
        return current.parentIndex() < 0 && !"none".equals(current.parent()) ? canonicalHost(current.parent()) : null;
    }
    private static String canonicalHost(String name) {
        return switch (name) { case "head" -> "Head"; case "head_overlay" -> "HeadOverlay"; case "body" -> "UpperBody"; case "body_overlay" -> "BodyOverlay"; case "left_arm" -> "LeftArm"; case "left_arm_overlay" -> "LeftArmOverlay"; case "right_arm" -> "RightArm"; case "right_arm_overlay" -> "RightArmOverlay"; case "left_leg" -> "LeftLeg"; case "left_leg_overlay" -> "LeftLegOverlay"; case "right_leg" -> "RightLeg"; case "right_leg_overlay" -> "RightLegOverlay"; default -> name; };
    }
}
