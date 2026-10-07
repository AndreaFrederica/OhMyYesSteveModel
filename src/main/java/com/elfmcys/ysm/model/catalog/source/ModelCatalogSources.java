package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.catalog.PathsUtils;

import java.nio.file.Path;
import java.util.List;

public final class ModelCatalogSources {
    private ModelCatalogSources() {
    }

    public static List<ModelCatalogSource> sources() {
        var reloadable = reloadableSources();
        return List.of(builtin(), reloadable.get(0), reloadable.get(1));
    }

    public static ModelCatalogSource builtin() {
        return new ModelCatalogSource(CatalogRootKind.BUILTIN,
                AssetPaths.builtinModelsResource(), false);
    }

    public static Path builtinIndex() {
        return PathsUtils.containerRoot(YesSteveModel.MOD_ID)
                .resolve("assets", YesSteveModel.MOD_ID, "builtin-index.json");
    }

    public static List<ModelCatalogSource> reloadableSources() {
        return List.of(
                new ModelCatalogSource(CatalogRootKind.CUSTOM, AssetPaths.customModelsRoot(), true),
                new ModelCatalogSource(CatalogRootKind.AUTH, AssetPaths.authModelsRoot(), true));
    }

    public static Path customPath() {
        return AssetPaths.customModelsRoot();
    }
}
