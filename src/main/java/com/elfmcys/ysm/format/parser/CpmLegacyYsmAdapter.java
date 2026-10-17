package com.elfmcys.ysm.format.parser;

import cc.sirrus.ysmlib.cpm.CpmCompiledModel;
import cc.sirrus.ysmlib.cpm.CpmDocument;
import com.elfmcys.ysm.format.parser.pojo.model.*;
import java.io.IOException;
import java.util.*;

/** Legacy CustomModel -> YSM adapter.  No Modern geometry rules belong here. */
final class CpmLegacyYsmAdapter {
    private CpmLegacyYsmAdapter() {}

    static GeoBuilder.Result build(CpmDocument.Legacy source) throws IOException {
        return build(source, null);
    }

    static GeoBuilder.Result build(CpmDocument.Legacy source, CpmTextureAtlas.Layout atlas) throws IOException {
        var compiled = cc.sirrus.ysmlib.YsmRuntime.cpm().compileCanonical(source);
        var out = new GeoModel(); out.formatVersion = "1.12.0"; out.minecraftGeometry = new ArrayList<>();
        var geometry = new Geometry();
        geometry.description.textureWidth = atlas == null ? compiled.textureWidth() : atlas.width();
        geometry.description.textureHeight = atlas == null ? compiled.textureHeight() : atlas.height();
        var byBone = new LinkedHashMap<Integer, Bone>();
        for (var sourceBone : compiled.bones()) {
            var bone = new Bone(); bone.name = sourceBone.name();
            bone.parent = sourceBone.parent() < 0 ? null : compiled.bones().get(sourceBone.parent()).name();
            bone.pivot = CpmYsmModelAdapter.vec(sourceBone.pivot());
            bone.rotation = CpmYsmModelAdapter.vec(sourceBone.rotation());
            bone.cubes = new ArrayList<>(); byBone.put(sourceBone.index(), bone); geometry.bones.add(bone);
        }
        for (var primitive : compiled.primitives()) {
            var target = byBone.get(primitive.bone());
            if (target == null) throw new IOException("Legacy primitive references missing bone " + primitive.bone());
            var cube = new Cube();
            cube.origin = CpmYsmModelAdapter.vec(primitive.origin());
            cube.size = CpmYsmModelAdapter.vec(primitive.size());
            cube.pivot = CpmYsmModelAdapter.vec(primitive.pivot());
            cube.rotation = CpmYsmModelAdapter.vec(primitive.rotation());
            cube.inflate = (float) primitive.inflate(); cube.mirror = primitive.mirror();
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
        return GeoBuilder.build(out).withScales(1f, 1f).withTransformMode(1);
    }
}
