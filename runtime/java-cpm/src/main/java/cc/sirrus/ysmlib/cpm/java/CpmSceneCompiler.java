package cc.sirrus.ysmlib.cpm.java;

import cc.sirrus.ysmlib.cpm.*;
import java.util.*;

/**
 * Public CPM compiler facade. The ordinary compile method preserves authored
 * source order for tools. Player canonical compilation is dispatched to two
 * format-specific compilers; no Legacy/Modern conversion rules live here.
 */
public final class CpmSceneCompiler {
    private CpmSceneCompiler() {}

    /** Format-neutral source IR, with authored coordinates untouched. */
    public static CpmCompiledModel compile(CpmDocument document) {
        Objects.requireNonNull(document);
        var bones = new ArrayList<CpmCompiledModel.Bone>();
        var primitives = new ArrayList<CpmCompiledModel.Primitive>();
        var textures = new LinkedHashMap<String, CpmCompiledModel.Texture>();
        if (document instanceof CpmDocument.Legacy legacy) {
            for (var b : legacy.bones()) {
                int i = bones.size();
                bones.add(new CpmCompiledModel.Bone(i, b.id(), b.parentIndex(),
                        CpmCompiledModel.Vec3.ZERO, CpmCompiledModel.Vec3.ZERO));
                for (var box : b.boxes()) {
                    var c = box.coordinates();
                    primitives.add(new CpmCompiledModel.Primitive(CpmCompiledModel.Kind.CUBE, i,
                            vec(c, 0), vec(c, 3), CpmCompiledModel.Vec3.ZERO,
                            CpmCompiledModel.Vec3.ZERO, c, box.textureOffset(), box.sizeAdd(),
                            box.mirror(), uv(box.textureOffset()), "", box.source()));
                }
                for (var quad : b.quads()) primitives.add(new CpmCompiledModel.Primitive(
                        CpmCompiledModel.Kind.QUAD, i, CpmCompiledModel.Vec3.ZERO,
                        CpmCompiledModel.Vec3.ZERO, CpmCompiledModel.Vec3.ZERO,
                        CpmCompiledModel.Vec3.ZERO, quad.coordinates(), quad.textureOffset(),
                        quad.sizeAdd(), false, uv(quad.textureOffset()), "", quad.source()));
            }
        } else {
            var remake = (CpmDocument.Remake) document;
            for (var part : remake.parts()) for (var model : part.geometry().models()) {
                var local = new LinkedHashMap<String, Integer>();
                for (var b : model.bones()) {
                    int parent = b.parent() == null || b.parent().isEmpty()
                            ? -1 : local.getOrDefault(b.parent(), -1);
                    int i = bones.size(); local.put(b.name(), i);
                    bones.add(new CpmCompiledModel.Bone(i, b.name(), parent, vec(b.pivot()), vec(b.rotation())));
                    for (var cube : b.cubes()) primitives.add(new CpmCompiledModel.Primitive(
                            CpmCompiledModel.Kind.CUBE, i, vec(cube.origin()), vec(cube.size()),
                            vec(cube.pivot()), vec(cube.rotation()), List.of(), List.of(),
                            cube.inflate(), cube.mirror(), cube.uv() == null
                                    ? CpmValue.NullValue.INSTANCE : cube.uv(), part.texture(), cube.source()));
                }
                if (!part.texture().isEmpty()) textures.putIfAbsent(part.texture(),
                        new CpmCompiledModel.Texture(part.texture(), part.packageTexturePath()));
            }
        }
        return new CpmCompiledModel(bones, primitives, textures, document);
    }

    /** Dispatch only; format semantics are owned by the selected converter. */
    public static CpmCompiledModel compileCanonical(CpmDocument document) {
        Objects.requireNonNull(document);
        if (document instanceof CpmDocument.Legacy legacy) return CpmLegacySceneConverter.compile(legacy);
        return CpmModernSceneConverter.compile((CpmDocument.Remake) document);
    }

    private static CpmCompiledModel.Vec3 vec(List<Double> values, int offset) {
        return new CpmCompiledModel.Vec3(values.get(offset), values.get(offset + 1), values.get(offset + 2));
    }
    private static CpmCompiledModel.Vec3 vec(CpmGeometry.Vector value) {
        return new CpmCompiledModel.Vec3(value.x(), value.y(), value.z());
    }
    private static CpmValue uv(List<Double> values) {
        return new CpmValue.ArrayValue(values.stream()
                .map(v -> (CpmValue) new CpmValue.NumberValue(Double.toString(v))).toList());
    }
}
