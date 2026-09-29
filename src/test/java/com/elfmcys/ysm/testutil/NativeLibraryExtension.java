package com.elfmcys.ysm.testutil;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Compatibility annotation for migrated tests: no native library is loaded. */
public final class NativeLibraryExtension implements BeforeAllCallback {
    @Override public void beforeAll(ExtensionContext context) {
        System.setProperty("ysm.runtime.javaOnly", "true");
    }
}
