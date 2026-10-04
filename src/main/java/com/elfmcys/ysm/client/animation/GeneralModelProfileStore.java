package com.elfmcys.ysm.client.animation;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.service.ClientModelService;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Host filesystem authoring boundary. Runtime always consumes the profile embedded in the distributed package. */
public final class GeneralModelProfileStore {
    private GeneralModelProfileStore() {}
    /** Filesystem location and catalog identity are separate when the custom root is a junction. */
    public record EditableSource(Path path, String catalogPath) {}
    public static EditableSource source(Hash256 id) throws IOException {
        var entry=ClientModelService.instance().catalog().find(id).orElseThrow(()->new IOException("Model is no longer in the catalog"));
        if(entry.origin()!=com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry.Origin.CUSTOM)
            throw new IOException("Only local custom source models can be edited; import a local copy first");
        return resolveSource(AssetPaths.customModelsRoot(),entry.displayPath());
    }
    static EditableSource resolveSource(Path configuredRoot,String catalogPath) throws IOException {
        var relative=Path.of(catalogPath);
        if(relative.isAbsolute() || relative.normalize().startsWith("..")) throw new IOException("Model source escapes custom directory");
        var root=configuredRoot.toRealPath();var path=root.resolve(relative).normalize();
        if(!path.startsWith(root) || !path.toRealPath().startsWith(root)) throw new IOException("Model source escapes custom directory");
        String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
        if(List.of(".pmx",".pmd",".vrm",".gltf",".glb",".fbx",".yscene").stream().noneMatch(name::endsWith))
            throw new IOException("Edit the original model source, not a compiled container");
        return new EditableSource(path,catalogPath);
    }
    public static Path sidecar(Path source) { return source.resolveSibling(source.getFileName()+SceneModelProfile.SUFFIX); }
    public static byte[] read(Path source) throws IOException {
        var file=sidecar(source);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) return null;
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file)>1024*1024) throw new IOException("Invalid model profile file: "+file);
        return Files.readAllBytes(file);
    }
    public static byte[] save(Path source,SceneModelProfile profile,byte[] expected) throws IOException {
        if(!Arrays.equals(read(source),expected)) throw new IOException("Profile changed externally. Reopen the editor before saving.");
        var bytes=YsmRuntime.scenes().writeModelProfile(profile).copy();
        // Validate serialized data before replacing the user's file.
        YsmRuntime.scenes().readModelProfile(new ByteData(bytes));
        var file=sidecar(source);var temp=Files.createTempFile(file.getParent(),".omysm-",".tmp");
        try {
            Files.write(temp,bytes);
            Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
        return bytes;
    }
    public static SceneModelProfile migrateDraft(ModelRenderTarget model) {
        var payload=model.generalMeshResources();var profile=payload.profile();
        if(payload.assets().source().files().containsKey(SceneModelProfile.PACKAGE_PATH)) return profile;
        var mappings=new LinkedHashMap<String,SceneModelProfile.Action>();
        for(var e:GeneralAnimationMappingStore.instance().entries(model.modelHash()).entrySet()) {
            var selection=e.getValue().selection();String path;
            if(selection.equals(ScenePackagePlayback.Selection.REST)) path="REST";
            else if(selection.sourceId().startsWith("@ysm/generated/")) path=selection.sourceId();
            else {
                var clip=payload.animations().stream().filter(a->a.selection().equals(selection)).findFirst().orElse(null);
                if(clip==null) continue;path=clip.sourcePath();
            }
            mappings.put(e.getKey(),new SceneModelProfile.Action(path,selection.clip(),e.getValue().loop()));
        }
        return profile.withActions(mappings);
    }
}
