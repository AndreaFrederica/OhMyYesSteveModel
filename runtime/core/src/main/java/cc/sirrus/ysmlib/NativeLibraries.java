package cc.sirrus.ysmlib;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Discovers only our independently distributed accelerators. No official library search. */
final class NativeLibraries {
    private NativeLibraries() {}

    static Path find(String capability) {
        String override = System.getProperty("ysm.runtime." + capability + "Library", "");
        if (!override.isBlank()) return Path.of(override);
        Path directory = Path.of(System.getProperty("ysm.runtime.nativeDir", "ysmlib/natives"));
        return find(directory, capability, System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    static Path find(Path directory, String capability, String osName, String architecture) {
        String os = osName.toLowerCase(Locale.ROOT);
        String platform = os.startsWith("windows") ? "windows"
                : os.startsWith("mac") ? "macos" : os.startsWith("linux") ? "linux" : null;
        String arch = switch (architecture.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
        if (platform == null || arch == null) return null;
        String name = (platform.equals("windows") ? "" : "lib") + "ysmlib_" + capability
                + (platform.equals("windows") ? ".dll" : platform.equals("macos") ? ".dylib" : ".so");
        Path library = directory.resolve(platform + "-" + arch).resolve(name);
        return Files.isRegularFile(library) ? library : null;
    }
}
