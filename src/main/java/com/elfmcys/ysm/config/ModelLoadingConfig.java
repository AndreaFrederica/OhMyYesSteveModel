package com.elfmcys.ysm.config;

import com.elfmcys.ysm.model.catalog.CatalogLoadingPolicy;
import net.minecraftforge.common.ForgeConfigSpec;

public final class ModelLoadingConfig {
    public static ForgeConfigSpec.IntValue CATALOG_WORKERS;
    public static ForgeConfigSpec.IntValue CLIENT_WORKERS;
    public static ForgeConfigSpec.IntValue QUEUED_INPUTS;
    public static ForgeConfigSpec.IntValue PUBLICATIONS_PER_TICK;
    public static ForgeConfigSpec.IntValue PUBLICATION_MILLIS;

    private ModelLoadingConfig() { }

    public static void init(ForgeConfigSpec.Builder builder) {
        builder.push("model_loading");
        CATALOG_WORKERS = builder.comment("Catalog workers; applies to the next scan.")
                .defineInRange("CatalogWorkers", 1, 1, 8);
        CLIENT_WORKERS = builder.comment("Client model/asset worker concurrency; applied on the next client tick.")
                .defineInRange("ClientWorkers", 2, 1, 8);
        QUEUED_INPUTS = builder.comment("Extra admitted scan inputs beyond the worker count. Remaining files wait in the inventory; none are dropped.")
                .defineInRange("QueuedInputs", 4, 0, 64);
        PUBLICATIONS_PER_TICK = builder.comment("Maximum results per tick, separately for catalog and render resources.")
                .defineInRange("PublicationsPerTick", 2, 1, 32);
        PUBLICATION_MILLIS = builder.comment("Soft time budget per result batch in milliseconds. One indivisible result/upload may exceed it.")
                .defineInRange("PublicationMillis", 2, 1, 10);
        builder.pop();
    }

    public static CatalogLoadingPolicy catalogPolicy() {
        return new CatalogLoadingPolicy(CATALOG_WORKERS.get(), QUEUED_INPUTS.get(),
                PUBLICATIONS_PER_TICK.get(), PUBLICATION_MILLIS.get());
    }
}
