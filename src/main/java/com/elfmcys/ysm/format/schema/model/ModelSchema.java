package com.elfmcys.ysm.format.schema.model;

import java.io.IOException;

/** Explicit container dispatch. Adding a backend never reinterprets a legacy definition blob. */
public enum ModelSchema {
    MC(ModelFileConstant.SCHEMA_ID, ModelFileConstant.CURRENT_VERSION.toString()),
    GENERAL_MESH("ysm/general-mesh", "0.1.0-unstable");

    private final String id;
    private final String version;

    ModelSchema(String id, String version) {
        this.id = id;
        this.version = version;
    }

    public String id() { return id; }
    public String version() { return version; }

    public static ModelSchema require(String id) throws IOException {
        for (var schema : values()) {
            if (schema.id.equals(id)) return schema;
        }
        throw new IOException("Unsupported model schema: " + id);
    }
}
