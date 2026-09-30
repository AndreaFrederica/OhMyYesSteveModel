package com.elfmcys.ysm.model.catalog;

/** Owner-published observation; totals count source files and pack descriptions, not models. */
public record CatalogScanProgress(Stage stage, int total, int completed, int waiting,
                                  int inFlight, int errors) {
    public enum Stage { IDLE, DISCOVERING, LOADING, FINALIZING, COMPLETE, FAILED }
    public static final CatalogScanProgress IDLE =
            new CatalogScanProgress(Stage.IDLE, 0, 0, 0, 0, 0);

    public boolean active() {
        return stage == Stage.DISCOVERING || stage == Stage.LOADING || stage == Stage.FINALIZING;
    }
}
