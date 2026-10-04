package com.elfmcys.ysm.model.catalog.source;

public enum ModelSourceKind {
    DIRECT_CONTAINER,
    CURRENT_RAW_DIRECTORY,
    CURRENT_RAW_ARCHIVE,
    /** A portable scene source such as PMX, glTF, VRM or FBX. */
    GENERIC_RAW_FILE,
    /** A Unity gzip/tar package containing one or more exported FBX assets. */
    GENERIC_UNITY_PACKAGE,
    LEGACY_ARCHIVE,
    UNSUPPORTED_YSM
}
