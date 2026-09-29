package com.elfmcys.ysm.format.vfs;

import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.annotation.Borrowed;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class DirectoryView implements VirtualFileSystem {
    private final VirtualFileSystem underlying;
    private final String dir;

    DirectoryView(VirtualFileSystem underlying, String dir) {
        this.underlying = underlying;
        if (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        if (!dir.endsWith("/")) {
            dir = dir + "/";
        }
        this.dir = dir;
    }

    @Override
    public DirectoryView directoryView(String path) {
        return new DirectoryView(underlying, prependPath(path));
    }

    private String prependPath(@Nullable String path) {
        if (path == null) {
            return this.dir;
        }
        if (!path.startsWith("/")) {
            return this.dir + path;
        }
        return this.dir + path.substring(1);
    }

    @Override
    public @NotNull String[] listFiles(@Nullable String path) {
        return underlying.listFiles(prependPath(path));
    }

    @Override
    public @NotNull String[] listDirectories(@Nullable String path) {
        return underlying.listDirectories(prependPath(path));
    }

    @Override
    public boolean hasFile(String fileName) {
        return underlying.hasFile(prependPath(fileName));
    }

    @Borrowed
    @Override
    public @Nullable UniBuffer getFile(String fileName) {
        return underlying.getFile(prependPath(fileName));
    }
}
