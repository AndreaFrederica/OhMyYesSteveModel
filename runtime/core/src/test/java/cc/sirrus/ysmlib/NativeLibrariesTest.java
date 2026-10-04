package cc.sirrus.ysmlib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class NativeLibrariesTest {
    @TempDir Path directory;

    @Test void discoversOnlyMatchingPlatformAndOurLibraryNames() throws Exception {
        Path windows = Files.createDirectories(directory.resolve("windows-x64"));
        Files.write(windows.resolve("ysm.dll"), new byte[0]);
        assertNull(NativeLibraries.find(directory, "codec", "Windows 11", "amd64"));
        Path codec = Files.write(windows.resolve("ysmlib_codec.dll"), new byte[0]);
        assertEquals(codec, NativeLibraries.find(directory, "codec", "Windows 11", "amd64"));
        assertNull(NativeLibraries.find(directory, "render", "Windows 11", "amd64"));
        assertNull(NativeLibraries.find(directory, "codec", "Windows 11", "aarch64"));
        assertNull(NativeLibraries.find(directory, "codec", "Linux", "x86_64"));
        assertNull(NativeLibraries.find(directory, "codec", "Other", "amd64"));
    }

    @Test void resolvesMacAndLinuxArmNames() throws Exception {
        for (var platform : new String[]{"macos", "linux"}) {
            var parent = Files.createDirectories(directory.resolve(platform + "-arm64"));
            var file = Files.write(parent.resolve("libysmlib_render." + (platform.equals("macos") ? "dylib" : "so")), new byte[0]);
            assertEquals(file, NativeLibraries.find(directory, "render",
                    platform.equals("macos") ? "Mac OS X" : "Linux", "aarch64"));
        }
    }

    @Test void discoversPhysicsOnVerifiedX64Platforms() throws Exception {
        for (var platform : new String[]{"windows", "linux"}) {
            var parent = Files.createDirectories(directory.resolve(platform + "-x64"));
            var name = platform.equals("windows") ? "ysmlib_physics.dll" : "libysmlib_physics.so";
            var file = Files.write(parent.resolve(name), new byte[0]);
            assertEquals(file, NativeLibraries.find(directory, "physics",
                    platform.equals("windows") ? "Windows 11" : "Linux", "x86_64"));
        }
    }
}
