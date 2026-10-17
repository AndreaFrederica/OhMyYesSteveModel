package com.elfmcys.ysm.model.catalog.source;

public enum ModelSourceKind {
    DIRECT_CONTAINER,
    CURRENT_RAW_DIRECTORY,
    /** CustomModel/CustomPlayerModel directory rooted at model.json. */
    CPM_RAW_DIRECTORY,
    CPM_RAW_ARCHIVE,
    CURRENT_RAW_ARCHIVE,
    /** A portable scene source such as PMX, glTF, VRM or FBX. */
    GENERIC_RAW_FILE,
    /** A Unity gzip/tar package containing one or more exported FBX assets. */
    GENERIC_UNITY_PACKAGE,
    /** A normal ZIP workspace containing an FBX/glTF/VRM/PMX source and assets. */
    GENERIC_WORKSPACE_ARCHIVE,
    LEGACY_ARCHIVE,
    UNSUPPORTED_YSM
}
