package com.elfmcys.ysm.model.resource.client.render;

/** Read-only progress snapshot for the in-game loading overlay. */
public record ModelLoadProgress(boolean active, String stage, String operation, String currentFile,
                                String model, long completed, long total,
                                int completedItems, int totalItems) {
    public static final ModelLoadProgress IDLE = new ModelLoadProgress(
            false, "idle", "idle", "", "", 0, 0, 0, 0);

    /** Compatibility constructor for callers that only have one progress dimension. */
    public ModelLoadProgress(boolean active, String stage, String model, long completed, long total) {
        this(active, stage, stage, model, model, completed, total, 0, 0);
    }

    public ModelLoadProgress {
        if (stage == null || operation == null || currentFile == null || model == null
                || completed < 0 || total < 0 || completedItems < 0 || totalItems < 0)
            throw new IllegalArgumentException("Invalid model load progress");
    }
}
