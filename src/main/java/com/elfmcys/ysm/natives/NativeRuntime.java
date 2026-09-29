package com.elfmcys.ysm.natives;

import org.apache.logging.log4j.Level;

import java.util.Objects;

public final class NativeRuntime {
    private static final int CONFIG_VERSION = 1;
    private static final long VERSION_MASK = 0xFFL;
    private static final int LOG_LEVEL_SHIFT = 8;
    private static final long LOG_LEVEL_MASK = 0x7L << LOG_LEVEL_SHIFT;
    private static final long JAVA_CONFIG_MASK = VERSION_MASK | LOG_LEVEL_MASK;
    private static final long TRACY_ENABLED_MASK = 1L << 8;
    private static final long DEBUG_LOGGING_ENABLED_MASK = 1L << 9;
    private static final long NATIVE_CONFIG_MASK = VERSION_MASK | TRACY_ENABLED_MASK | DEBUG_LOGGING_ENABLED_MASK;

    private static volatile NativeConfig nativeConfig;
    private static long initializedJavaConfig = -1;

    private NativeRuntime() {
    }

    public static synchronized NativeConfig initialize(JavaConfig javaConfig) {
        Objects.requireNonNull(javaConfig, "javaConfig");
        var packedJavaConfig = javaConfig.pack();
        if (nativeConfig != null) {
            if (initializedJavaConfig != packedJavaConfig) {
                throw new IllegalStateException("Native runtime was initialized with different Java config");
            }
            return nativeConfig;
        }

        var packedNativeConfig = CONFIG_VERSION;
        var decoded = NativeConfig.decode(packedNativeConfig);
        initializedJavaConfig = packedJavaConfig;
        nativeConfig = decoded;
        return decoded;
    }

    static boolean isTracyEnabled() {
        var config = nativeConfig;
        return config != null && config.tracyEnabled();
    }

    public record JavaConfig(int logLevel) {
        public JavaConfig {
            if (logLevel < 0 || logLevel > 7) {
                throw new IllegalArgumentException("Invalid native log level: " + logLevel);
            }
        }

        public static JavaConfig fromLog4j(Level level) {
            Objects.requireNonNull(level, "level");
            if (level == Level.ALL) {
                return new JavaConfig(0);
            }
            if (level == Level.OFF) {
                return new JavaConfig(7);
            }

            int value = level.intLevel();
            if (value >= Level.TRACE.intLevel()) {
                return new JavaConfig(1);
            }
            if (value >= Level.DEBUG.intLevel()) {
                return new JavaConfig(2);
            }
            if (value >= Level.INFO.intLevel()) {
                return new JavaConfig(3);
            }
            if (value >= Level.WARN.intLevel()) {
                return new JavaConfig(4);
            }
            if (value >= Level.ERROR.intLevel()) {
                return new JavaConfig(5);
            }
            if (value >= Level.FATAL.intLevel()) {
                return new JavaConfig(6);
            }
            return new JavaConfig(7);
        }

        long pack() {
            return CONFIG_VERSION | ((long) logLevel << LOG_LEVEL_SHIFT);
        }
    }

    public record NativeConfig(int version, boolean tracyEnabled, boolean debugLoggingEnabled) {
        private static NativeConfig decode(long packed) {
            if ((packed & ~NATIVE_CONFIG_MASK) != 0) {
                throw new IllegalStateException("Native runtime returned unknown config bits: " + packed);
            }
            var version = (int) (packed & VERSION_MASK);
            if (version != CONFIG_VERSION) {
                throw new IllegalStateException("Native runtime config version mismatch: " + version);
            }
            return new NativeConfig(
                    version,
                    (packed & TRACY_ENABLED_MASK) != 0,
                    (packed & DEBUG_LOGGING_ENABLED_MASK) != 0
            );
        }
    }


}
