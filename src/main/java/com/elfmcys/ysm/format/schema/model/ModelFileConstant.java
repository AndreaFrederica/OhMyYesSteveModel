package com.elfmcys.ysm.format.schema.model;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

public final class ModelFileConstant {
    public static final String SCHEMA_ID = "mixel/character";
    public static final String MANIFEST_CHUNK_NAME = "manifest";
    public static final String THUMB_BUTTON_CHUNK_NAME = "thumb-button";
    public static final String THUMB_ICON_CHUNK_NAME = "thumb-icon";
    /** Optional source closure for models imported from CustomModel/CPM. */
    public static final String CPM_SOURCE_CHUNK_NAME = "cpm-source";
    public static final DefaultArtifactVersion CURRENT_VERSION = new DefaultArtifactVersion("0.1.0-unstable");
    public static final short PROP_VERSION = 0;
    public static final short PROP_VENDOR = 1;
    public static final short PROP_MODEL_ID = 2;

    private ModelFileConstant() {
    }
}
