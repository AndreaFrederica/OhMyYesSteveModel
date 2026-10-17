package com.elfmcys.ysm.format.schema.baked.model;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

public class BakedModelConstant {
    public static String SCHEMA_ID = "ysm/baked-model";
    public static String MANIFEST_CHUNK_NAME = "manifest";
    public static String BAKE_HASH_CHUNK_NAME = "bake_hash";
    public static String MODEL_CHUNK_NAME = "model";
    // Bumped with the CPM UV bake fix.  The baked payload contains the
    // source face/UV ordering, so old cache entries must never be reused
    // after that semantic change.
    public static DefaultArtifactVersion CURRENT_VERSION = new DefaultArtifactVersion("0.2.1-unstable");
    public static short PROP_VERSION = 0;
}
