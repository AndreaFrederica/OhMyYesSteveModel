package com.elfmcys.ysm.format.vfs;

import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.annotation.Borrowed;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface VirtualFileSystem {
    @NotNull
    String[] listFiles(@Nullable String path);

    @NotNull
    String[] listDirectories(@Nullable String path);

    @NotNull
    default String[] listFiles() {
        return listFiles(null);
    }

    @NotNull
    default String[] listDirectories() {
        return listDirectories(null);
    }

    boolean hasFile(String fileName);

    default VirtualFileSystem directoryView(String dir) {
        return new DirectoryView(this, dir);
    }

    @Borrowed
    @Nullable
    UniBuffer getFile(String fileName);

    // 有个格式拿不到解压后的 size，所以没有这个接口
}
