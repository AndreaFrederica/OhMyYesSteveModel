package com.elfmcys.ysm.format.parser;

import cc.sirrus.ysmlib.cpm.CpmCompiledModel;
import cc.sirrus.ysmlib.cpm.CpmDocument;
import cc.sirrus.ysmlib.cpm.CpmValue;
import com.elfmcys.ysm.format.parser.pojo.model.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Projects the Lib's compiled CPM IR into YSM's legacy GeoModel/bake pipeline.
 * CPM parsing, skeleton binding, coordinate conversion and locator semantics
 * are intentionally owned by {@code ysm-runtime-java-cpm}.
 */
public final class CpmYsmModelAdapter {
    private CpmYsmModelAdapter() {}

    public static GeoBuilder.Result build(CpmDocument source) throws IOException {
        Objects.requireNonNull(source);
        return source instanceof CpmDocument.Legacy
                ? CpmLegacyYsmAdapter.build((CpmDocument.Legacy) source)
                : CpmModernYsmAdapter.build((CpmDocument.Remake) source);
    }

    /** Builds geometry while remapping primitive UVs into a CPM texture atlas. */
    public static GeoBuilder.Result build(CpmDocument source, CpmTextureAtlas.Layout atlas) throws IOException {
        Objects.requireNonNull(source);
        Objects.requireNonNull(atlas);
        return source instanceof CpmDocument.Legacy
                ? CpmLegacyYsmAdapter.build((CpmDocument.Legacy) source, atlas)
                : CpmModernYsmAdapter.build((CpmDocument.Remake) source, atlas);
    }

    /** Loads one CPM directory through the Lib reader and projects it to YSM. */
    public static GeoBuilder.Result load(Path directory) throws IOException {
        Objects.requireNonNull(directory);
        var files = new LinkedHashMap<String, cc.sirrus.ysmlib.scene.ByteData>();
        try (var paths = Files.walk(directory)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                var relative = directory.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                files.put(relative, new cc.sirrus.ysmlib.scene.ByteData(Files.readAllBytes(path)));
            }
        }
        if (!files.containsKey("model.json")) {
            var candidates = files.keySet().stream()
                    .filter(path -> path.equalsIgnoreCase("model.json") || path.toLowerCase(Locale.ROOT).endsWith("/model.json"))
                    .toList();
            if (candidates.size() == 1) {
                String model = candidates.get(0);
                String prefix = model.substring(0, model.length() - "model.json".length());
                var normalized = new LinkedHashMap<String, cc.sirrus.ysmlib.scene.ByteData>();
                files.forEach((path, bytes) -> normalized.put(path.startsWith(prefix) ? path.substring(prefix.length()) : path, bytes));
                files = normalized;
            }
        }
        if (!files.containsKey("model.json")) throw new IOException("CPM directory has no model.json");
        return build(cc.sirrus.ysmlib.YsmRuntime.cpm().read(files, cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT));
    }

    static void applyLocators(CpmCompiledModel compiled, Geometry geometry) {
        var names = new HashSet<String>(); for (var bone : geometry.bones) names.add(bone.name);
        var byName = new LinkedHashMap<String, Bone>(); for (var bone : geometry.bones) byName.put(bone.name, bone);
        for (var declaration : compiled.locators()) {
            var target = byName.get(declaration.bone()); if (target == null) continue;
            String locator = locatorName(declaration.group()); if (locator == null) continue;
            if (locator.equals("LeftArm") || locator.equals("RightArm") || locator.equals("Background")) {
                target.locator = locator; continue;
            }
            // Equipment locators are materialized as empty child bones because
            // the existing YSM item/armor layers resolve them by bone name.
            if (names.contains(locator)) { target.locator = locator; continue; }
            var host = new Bone(); host.name = locator; host.parent = target.name; host.pivot = new float[]{0, 0, 0};
            host.rotation = new float[]{0, 0, 0}; host.cubes = new ArrayList<>();
            geometry.bones.add(geometry.bones.indexOf(target) + 1, host); names.add(locator); byName.put(locator, host);
        }
    }

    private static String locatorName(String group) {
        return switch (group) {
            case "first_person_left" -> "LeftArm";
            case "first_person_right" -> "RightArm";
            case "first_person_background" -> "Background";
            case "item_left", "held_item_left", "chestplate_left_arm" -> "LeftHandLocator";
            case "item_right", "held_item_right", "chestplate_right_arm" -> "RightHandLocator";
            case "cape", "elytra" -> "ElytraLocator";
            case "parrot_left", "shoulder_parrot_left" -> "LeftShoulderLocator";
            case "parrot_right", "shoulder_parrot_right" -> "RightShoulderLocator";
            case "helmet", "skull", "head_wearing" -> "Head";
            default -> null;
        };
    }

    static float[] vec(CpmCompiledModel.Vec3 value) { return new float[]{(float) value.x(), (float) value.y(), (float) value.z()}; }
    static boolean isCanonicalHost(String name) {
        return switch (name) {
            case "Root", "MAllBody", "AllBody", "UpBody", "UpperBody", "Arm",
                    "LeftArm", "LeftForeArm", "LeftHand", "RightArm", "RightForeArm", "RightHand",
                    "AllHead", "MHead", "Head", "DownBody", "Leg", "LeftLeg", "LeftLowerLeg", "LeftFoot",
                    "RightLeg", "RightLowerLeg", "RightFoot", "HeadOverlay", "BodyOverlay", "LeftArmOverlay",
                    "RightArmOverlay", "LeftLegOverlay", "RightLegOverlay" -> true;
            default -> false;
        };
    }
    static float[] modernPoint(CpmCompiledModel.Vec3 value) {
        // ElementParser's (24-y) value is a ModelRenderer local position.
        // The YSM geometry payload is already in the final Bedrock frame, in
        // which the authored CPM pivot is [x,y,z].
        return new float[]{(float) value.x(), (float) value.y(), (float) value.z()};
    }
    static float[] modernCubeOrigin(CpmCompiledModel.Vec3 origin, CpmCompiledModel.Vec3 size) {
        // ElementParser.parseBox stores y=pivot.y-origin.y-size.y.  After
        // ModelBone.instantiate the root is reflected around the 24 pixel
        // model origin; in YSM's reflected frame the absolute cube interval
        // is therefore [24-origin.y-size.y, 24-origin.y].  X is reflected by
        // GeoBuilder itself, so its authored minimum remains unchanged here.
        return new float[]{(float) origin.x(),
                (float) (24d - origin.y() - size.y()),
                (float) origin.z()};
    }
    /** ElementParser converts a cube's absolute origin to ModelRenderer-local coordinates. */
    static float[] modernCubeOriginRelative(CpmCompiledModel.Vec3 origin,
                                             CpmCompiledModel.Vec3 size,
                                             CpmCompiledModel.Vec3 bonePivot) {
        return new float[]{
                (float) (-origin.x() + bonePivot.x() - size.x()),
                (float) (bonePivot.y() - origin.y() - size.y()),
                (float) (origin.z() - bonePivot.z())};
    }
    /** ElementParser stores an explicit cube pivot as a local ModelRenderer translation. */
    static float[] modernCubePivotRelative(CpmCompiledModel.Vec3 cubePivot,
                                            CpmCompiledModel.Vec3 bonePivot) {
        return new float[]{
                (float) (bonePivot.x() - cubePivot.x()),
                (float) (bonePivot.y() - cubePivot.y()),
                (float) (cubePivot.z() - bonePivot.z())};
    }
    static CpmCompiledModel.Vec3 modernBonePivotRelative(CpmCompiledModel.Vec3 pivot,
                                                         CpmCompiledModel.Vec3 parentPivot) {
        return new CpmCompiledModel.Vec3(pivot.x() - parentPivot.x(),
                pivot.y() - parentPivot.y(), pivot.z() - parentPivot.z());
    }
    static float[] modernGeoBuilderRotation(CpmCompiledModel.Vec3 value) {
        // ModelBone.getQuaternion() is Rz(source.z) * Ry(source.y) *
        // Rx(source.x).  GeoBuilder reflects the model frame on X and
        // converts its input as [-x,-y,+z]; to obtain the reflected CPM
        // transform Rz(-source.z) * Ry(-source.y) * Rx(-source.x), feed it
        // [source.x, source.y, -source.z].
        return new float[]{(float) value.x(), (float) value.y(), (float) -value.z()};
    }
    /** ElementParser assigns cube [x,y,z] to ModelRenderer.yRot,xRot,zRot. */
    static float[] modernCubeGeoBuilderRotation(CpmCompiledModel.Vec3 value) {
        // GeoBuilder applies Rz(r2) * Ry(-r1) * Rx(-r0).  ElementParser's
        // ModelRenderer applies Rz(source.z) * Ry(source.x) * Rx(source.y),
        // therefore the exact inverse encoding is [-source.y,-source.x,
        // source.z].  This is a cube rule and is independent of bone rest
        // rotation.
        return new float[]{(float) -value.y(), (float) -value.x(), (float) value.z()};
    }
    static CubeUv uv(CpmValue value, List<Double> fallback) {
        if (value instanceof CpmValue.ArrayValue array && array.values().size() >= 2)
            return CubeUv.box(new float[]{number(array, 0), number(array, 1)});
        if (value instanceof CpmValue.ObjectValue object) return perFace(object);
        return CubeUv.box(new float[]{fallback.size() > 0 ? fallback.get(0).floatValue() : 0, fallback.size() > 1 ? fallback.get(1).floatValue() : 0});
    }

    /**
     * Encodes a CPM box UV rectangle for GeoBuilder's reflected X face order.
     * The source Box/ModelRenderer face vertices are rotated by two corners on
     * five faces and reversed on the emitted east face after GeoBuilder's X
     * reflection.  A box UV pair cannot express that per-face winding, so the
     * adapter expands it into six FaceUv records.  This is shared only as a
     * mechanical UV encoding helper; Legacy and Modern still choose their own
     * source primitive and pose semantics.
     */
    static CubeUv reflectedBoxUv(CpmValue value, List<Double> fallback, CpmCompiledModel.Vec3 size) {
        if (!(value instanceof CpmValue.ArrayValue array) && !(fallback != null && fallback.size() >= 2))
            return uv(value, fallback == null ? List.of() : fallback);
        float u = value instanceof CpmValue.ArrayValue array ? number(array, 0)
                : fallback.get(0).floatValue();
        float v = value instanceof CpmValue.ArrayValue array ? number(array, 1)
                : fallback.get(1).floatValue();
        float dx = (float) Math.abs(size.x()), dy = (float) Math.abs(size.y()), dz = (float) Math.abs(size.z());
        var faces = new UvFaces();
        // GeoBuilder order: west/east/north/south/up/down.  The source
        // rectangles are xmin/xmax/zmin/zmax/ymax/ymin respectively.
        faces.west = rotated(u, v + dz, dz, dy, true, false);
        faces.east = rotated(u + dz + dx, v + dz, dz, dy, true, true);
        faces.north = rotated(u + dz, v + dz, dx, dy, true, false);
        faces.south = rotated(u + dz + dx + dz, v + dz, dx, dy, true, false);
        faces.up = rotated(u + dz + dx, v + dz, dx, -dz, true, false);
        faces.down = rotated(u + dz, v, dx, dz, true, false);
        return CubeUv.perFace(faces);
    }

    private static FaceUv rotated(float u, float v, float width, float height,
                                  boolean rotateTwice, boolean reverse) {
        var face = new FaceUv();
        if (rotateTwice) {
            face.uv = new float[]{u + width, v + height};
            face.uvSize = new float[]{-width, -height};
        } else {
            face.uv = new float[]{u, v};
            face.uvSize = new float[]{width, height};
        }
        face.flip = reverse;
        return face;
    }

    static CubeUv remapUv(CubeUv source, int offsetU, int offsetV) {
        return remapUv(source, offsetU, offsetV, 1, 1);
    }
    /** Remaps logical CPM texture pixels into the physical atlas image. */
    static CubeUv remapUv(CubeUv source, int offsetU, int offsetV, double scaleU, double scaleV) {
        if (source == null) return null;
        if (source.boxUv != null && source.boxUv.length >= 2) {
            return CubeUv.box(new float[]{(float) (source.boxUv[0] * scaleU + offsetU),
                    (float) (source.boxUv[1] * scaleV + offsetV)});
        }
        if (source.perFaceUv == null) return source;
        var faces = new UvFaces();
        faces.down = remapFace(source.perFaceUv.down, offsetU, offsetV, scaleU, scaleV);
        faces.east = remapFace(source.perFaceUv.east, offsetU, offsetV, scaleU, scaleV);
        faces.north = remapFace(source.perFaceUv.north, offsetU, offsetV, scaleU, scaleV);
        faces.south = remapFace(source.perFaceUv.south, offsetU, offsetV, scaleU, scaleV);
        faces.up = remapFace(source.perFaceUv.up, offsetU, offsetV, scaleU, scaleV);
        faces.west = remapFace(source.perFaceUv.west, offsetU, offsetV, scaleU, scaleV);
        return CubeUv.perFace(faces);
    }

    private static FaceUv remapFace(FaceUv source, int offsetU, int offsetV) {
        return remapFace(source, offsetU, offsetV, 1, 1);
    }
    private static FaceUv remapFace(FaceUv source, int offsetU, int offsetV, double scaleU, double scaleV) {
        if (source == null) return null;
        var result = new FaceUv();
        result.uv = source.uv == null || source.uv.length < 2
                ? source.uv : new float[]{(float) (source.uv[0] * scaleU + offsetU),
                (float) (source.uv[1] * scaleV + offsetV)};
        result.uvSize = source.uvSize == null ? null : new float[]{(float) (source.uvSize[0] * scaleU),
                (float) (source.uvSize[1] * scaleV)};
        result.flip = source.flip;
        return result;
    }
    private static CubeUv perFace(CpmValue.ObjectValue object) {
        var faces = new UvFaces(); faces.down = face(object, "down"); faces.east = face(object, "east"); faces.north = face(object, "north"); faces.south = face(object, "south"); faces.up = face(object, "up"); faces.west = face(object, "west"); return CubeUv.perFace(faces);
    }
    private static FaceUv face(CpmValue.ObjectValue object, String name) {
        var value = object.fields().get(name); if (!(value instanceof CpmValue.ObjectValue o)) return null;
        var result = new FaceUv(); result.uv = array(o.fields().get("uv")); result.uvSize = array(o.fields().get("uv_size")); return result;
    }
    private static float[] array(CpmValue value) {
        if (!(value instanceof CpmValue.ArrayValue a) || a.values().size() < 2) return null;
        return new float[]{number(a, 0), number(a, 1)};
    }
    private static float number(CpmValue.ArrayValue a, int index) {
        return number(a, index, 0);
    }
    private static float number(CpmValue.ArrayValue a, int index, float fallback) {
        var value = a.values().get(index);
        if (value instanceof CpmValue.NumberValue n) try { return Float.parseFloat(n.value()); } catch (NumberFormatException ignored) { }
        return fallback;
    }
}
