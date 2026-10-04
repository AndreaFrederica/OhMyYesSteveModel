package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.SceneImageUsage;
import cc.sirrus.ysmlib.scene.mmd.MmdMaterials;
import java.util.*;

/** Immutable texture requests for MMD's legacy numeric shader space, separate from glTF linear PBR. */
public final class MmdTextureBindings {
    // Enable the hardware mip chain for MMD as well.  The sampler then chooses
    // the level from screen-space derivatives, so Alt+Y's 256px target, the
    // game framebuffer and camera distance all select an appropriate LOD
    // without re-baking or swapping model materials on the render thread.
    private static final PreparedSceneTextures.Sampler REPEAT = new PreparedSceneTextures.Sampler(9729, 9987, 10497, 10497);
    private static final PreparedSceneTextures.Sampler CLAMP = new PreparedSceneTextures.Sampler(9729, 9987, 33071, 33071);
    private static final SceneImageUsage COLOR = new SceneImageUsage(SceneImageUsage.Transfer.SOURCE_NUMERIC, SceneImageUsage.Alpha.STRAIGHT);
    private static final SceneImageUsage OPAQUE = new SceneImageUsage(SceneImageUsage.Transfer.SOURCE_NUMERIC, SceneImageUsage.Alpha.OPAQUE);
    public record Material(PreparedSceneTextures.Key diffuse, PreparedSceneTextures.Key sphere, PreparedSceneTextures.Key toon) {}
    private final List<Material> materials;
    private final Set<PreparedSceneTextures.Key> requests;

    public MmdTextureBindings(String owner, MmdMaterials source) {
        var materials = new ArrayList<Material>();
        var requests = new LinkedHashSet<PreparedSceneTextures.Key>();
        for (var material : source.definitions()) {
            var diffuse = key(owner, material.diffuse(), REPEAT, COLOR);
            var sphere = key(owner, material.sphere(), material.sphereMode() == MmdMaterials.SphereMode.SUB_TEXTURE ? REPEAT : CLAMP,
                    material.sphereMode() == MmdMaterials.SphereMode.ADD ? OPAQUE : COLOR);
            var toon = key(owner, material.toon(), CLAMP, OPAQUE);
            materials.add(new Material(diffuse, sphere, toon));
            if (diffuse != null) requests.add(diffuse);
            if (sphere != null) requests.add(sphere);
            if (toon != null) requests.add(toon);
        }
        this.materials = List.copyOf(materials);
        this.requests = Collections.unmodifiableSet(requests);
    }
    public List<Material> materials() { return materials; }
    public Set<PreparedSceneTextures.Key> requests() { return requests; }
    private static PreparedSceneTextures.Key key(String owner, MmdMaterials.Image source,
                                                 PreparedSceneTextures.Sampler sampler, SceneImageUsage usage) {
        return source == null ? null : new PreparedSceneTextures.Key(source.key(owner), sampler, usage);
    }
}
