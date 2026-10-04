package com.elfmcys.ysm.client.animation;

import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.model.domain.SceneActionId;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.proto.mixel.common.StringPair;
import it.unimi.dsi.fastutil.objects.*;
import java.util.*;

/** Shared by the wheel, hotkeys, editor and entity playback. */
public final class GeneralAnimationActions {
    private GeneralAnimationActions() {}
    public record Action(SceneAnimation animation, boolean loop) {}
    public static Map<String,GeneralAnimationMappingStore.Entry> mappings(ModelRenderTarget model) {
        var payload=model.generalMeshResources();
        if(payload==null || !payload.assets().source().files().containsKey(SceneModelProfile.PACKAGE_PATH)) return GeneralAnimationMappingStore.instance().entries(model.modelHash());
        var result=new LinkedHashMap<String,GeneralAnimationMappingStore.Entry>();
        payload.profile().actions().forEach((name,action)-> {
            var selection=action.resolve(payload.assets().source());
            result.put(name,new GeneralAnimationMappingStore.Entry(selection.sourceId(),selection.clip(),action.loop()));
        });return Map.copyOf(result);
    }
    public static Action resolve(ModelRenderTarget model, String id) {
        if (model == null || model.generalMeshResources() == null) return null;
        String base = SceneActionId.base(id);
        var clips = model.generalMeshResources().animations();
        if (!base.isEmpty()) {
            boolean loop = SceneActionId.looping(base);
            return clips.stream().filter(a -> SceneActionId.of(a, loop).equals(base))
                    .map(a -> new Action(a, loop)).findFirst().orElse(null);
        }
        var mapping = mappings(model).get(id);
        if (mapping == null) return null;
        return clips.stream().filter(a -> a.selection().equals(mapping.selection()))
                .map(a -> new Action(a, mapping.loop())).findFirst().orElse(null);
    }
    /** Automatic state lookup; explicit editor drafts may deliberately bypass saved mappings. */
    public static SceneAnimation automatic(ModelRenderTarget model, String state) {
        if (model == null || model.generalMeshResources() == null) return null;
        var clips = model.generalMeshResources().animations();
        return clips.stream().filter(a -> matches(a, state)).findFirst().orElseGet(() ->
            clips.stream().filter(a -> a.selection().sourceId().equals("@ysm/generated/idle")).findFirst().orElse(null));
    }
    private static boolean matches(SceneAnimation animation, String state) {
        if (animation.selection().sourceId().startsWith("@ysm/generated/"))
            return animation.selection().sourceId().equals("@ysm/generated/" + state);
        String path = animation.sourcePath().replace('\\', '/');
        path = path.substring(path.lastIndexOf('/') + 1);
        int dot = path.lastIndexOf('.');if (dot >= 0) path = path.substring(0, dot);
        String key = normalize(state);
        return !key.isEmpty() && (normalize(animation.name()).equals(key) || normalize(path).equals(key));
    }
    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }
    public static ObjectList<StringPair> entries(ModelRenderTarget model) {
        if (model.generalMeshResources() == null) return model.info().getExtraAnimations();
        var rows = new ObjectArrayList<StringPair>();
        mappings(model).entrySet().stream()
            .sorted(Map.Entry.comparingByKey()).forEach(e -> {
                if (resolve(model, e.getKey()) != null) rows.add(pair(e.getKey(), e.getKey()));
            });
        for (var animation : model.generalMeshResources().animations()) {
            String name = animation.name().isBlank() ? animation.sourcePath() : animation.name();
            // Path disambiguates VMD files carrying the same model name.
            if (!animation.selection().sourceId().startsWith("@ysm/generated/")) name += " [" + animation.sourcePath() + "]";
            rows.add(pair(SceneActionId.of(animation, false), name));
            rows.add(pair(SceneActionId.of(animation, true), name + " ↻"));
        }
        rows.add(0, pair("#return", net.minecraft.network.chat.Component.translatable("gui.yes_steve_model.roulette.stop").getString()));
        return rows;
    }
    private static StringPair pair(String key, String label) {
        return StringPair.newBuilder().setKey(key).setValue(label).build();
    }
}
