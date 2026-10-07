package com.elfmcys.ysm.model.catalog;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.relauncher.FMLInjectionData;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class PathsUtils {
    private static Path containerRoot;

    private PathsUtils() {
    }

    public static Path gameDirectory() {
        var data = FMLInjectionData.data();
        if (data.length <= 6 || !(data[6] instanceof File home)) {
            throw new IllegalStateException("Cleanroom minecraftHome is unavailable");
        }
        return home.toPath().toAbsolutePath().normalize();
    }

    public static synchronized Path containerRoot(String modId) {
        if (containerRoot != null) {
            return containerRoot;
        }
        var container = Loader.instance().getIndexedModList().get(modId);
        if (container == null) {
            throw new IllegalStateException("Mod container is unavailable for modId: " + modId);
        }
        var resource = container.getResource().toPath().toAbsolutePath().normalize();
        // 开发环境
        if (Files.isDirectory(resource)) {
            containerRoot = resource;
            return containerRoot;
        }
        // 不应该发生
        if (!Files.isRegularFile(resource)) {
            throw new IllegalStateException("Mod resource container is unavailable: " + resource);
        }
        try {
            containerRoot = FileSystems.newFileSystem(resource, Map.of()).getPath("/");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open mod resource container: " + resource, e);
        }
        return containerRoot;
    }
}
