package com.elfmcys.ysm.model.catalog;

/** Host-neutral limits; input discovery is retained, only admitted work is bounded. */
public record CatalogLoadingPolicy(int workers, int queued, int publicationsPerTick,
                                   int publicationMillis) {
    public static final CatalogLoadingPolicy DEFAULT = new CatalogLoadingPolicy(1, 4, 2, 2);

    public CatalogLoadingPolicy {
        if (workers < 1 || workers > 8 || queued < 0 || queued > 64
                || publicationsPerTick < 1 || publicationsPerTick > 32
                || publicationMillis < 1 || publicationMillis > 10) {
            throw new IllegalArgumentException("Invalid model loading limits");
        }
    }
}
