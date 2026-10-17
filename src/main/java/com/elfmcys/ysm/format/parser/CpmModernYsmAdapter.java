package com.elfmcys.ysm.format.parser;

import cc.sirrus.ysmlib.cpm.CpmCompiledModel;
import cc.sirrus.ysmlib.cpm.CpmDocument;
import com.elfmcys.ysm.format.parser.pojo.model.*;
import java.io.IOException;
import java.util.*;

/** Modern CustomPlayerModel -> YSM adapter.  It owns all authored-pivot and ModelRenderer rules. */
final class CpmModernYsmAdapter {
    private CpmModernYsmAdapter() {}

    static GeoBuilder.Result build(CpmDocument.Remake source) throws IOException {
        return build(source, null);
    }

    static GeoBuilder.Result build(CpmDocument.Remake source, CpmTextureAtlas.Layout atlas) throws IOException {
        var compiled = cc.sirrus.ysmlib.YsmRuntime.cpm().compileCanonical(source);
        var out = new GeoModel(); out.formatVersion = "1.12.0"; out.minecraftGeometry = new ArrayList<>();
        var geometry = new Geometry();
        geometry.description.textureWidth = atlas == null ? compiled.textureWidth() : atlas.width();
        geometry.description.textureHeight = atlas == null ? compiled.textureHeight() : atlas.height();
        var byBone = new LinkedHashMap<Integer, Bone>();
        for (var sourceBone : compiled.bones()) {
            var bone = new Bone(); bone.name = sourceBone.name();
            bone.parent = sourceBone.parent() < 0 ? null : compiled.bones().get(sourceBone.parent()).name();
            // ModelBone.getTransform() is T(position) * R * S around the
            // instantiated local origin.  JavaModelState's non-zero pivot
            // branch is a different conjugation, so Modern local CPM bones
            // intentionally use a zero YSM pivot and carry all authored
            // parent-relative translation through ScriptBone.position.
            bone.pivot = new float[]{0, 0, 0};
            // The independent Modern pose session emits the complete
            // authored/script rotation.  Keep the static GeoBone channel at
            // zero so the rest rotation is consumed exactly once by
            // JavaModelState through the pose adapter.
            bone.rotation = new float[]{0, 0, 0};
            bone.cubes = new ArrayList<>(); byBone.put(sourceBone.index(), bone); geometry.bones.add(bone);
        }
        var doubleFace = new HashSet<String>();
        source.parts().stream().filter(CpmDocument.Part::doubleFace)
                .map(CpmDocument.Part::texture).forEach(doubleFace::add);
        for (var primitive : compiled.primitives()) {
            var target = byBone.get(primitive.bone());
            if (target == null) throw new IOException("Modern primitive references missing bone " + primitive.bone());
            var cube = new Cube();
            // ElementParser.parseBox first moves the authored cube into the
            // ModelRenderer frame: y = bonePivot.y - origin.y - size.y.
            // ModelBone.instantiate then reflects the complete model around
            // the 24 pixel root.  GeoBuilder already owns YSM's X reflection
            // but does not know CPM's 24 pixel origin, so preserve the exact
            // baked Y interval here.  Writing authored origin.y directly
            // leaves every modern cube above/below its source position.
            var sourceBone = compiled.bones().get(primitive.bone());
            cube.origin = CpmYsmModelAdapter.modernCubeOriginRelative(
                    primitive.origin(), primitive.size(), sourceBone.pivot());
            cube.size = CpmYsmModelAdapter.vec(primitive.size());
            cube.pivot = CpmYsmModelAdapter.modernCubePivotRelative(
                    primitive.pivot(), sourceBone.pivot());
            cube.rotation = CpmYsmModelAdapter.modernCubeGeoBuilderRotation(primitive.rotation());
            cube.inflate = (float) primitive.inflate(); cube.mirror = primitive.mirror();
            cube.doubleFace = doubleFace.contains(primitive.texture());
            cube.plane = primitive.kind() == CpmCompiledModel.Kind.QUAD;
            var uv = CpmYsmModelAdapter.reflectedBoxUv(primitive.uv(), primitive.textureOffset(), primitive.size());
            var region = atlas == null ? null : atlas.region(primitive.texture());
            cube.uv = region == null ? uv : CpmYsmModelAdapter.remapUv(uv, region.u(), region.v(),
                    region.imageWidth() / (double) Math.max(1, region.logicalWidth()),
                    region.imageHeight() / (double) Math.max(1, region.logicalHeight()));
            target.cubes.add(cube);
        }
        CpmYsmModelAdapter.applyLocators(compiled, geometry);
        out.minecraftGeometry.add(geometry);
        float scale = (float) compiled.defaultScale();
        return GeoBuilder.build(out).withScales(scale, scale).withTransformMode(2);
    }
}
