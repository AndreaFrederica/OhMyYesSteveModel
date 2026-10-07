package com.elfmcys.ysm.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.command.CommandTreeBase;
import org.jetbrains.annotations.NotNull;

public abstract class SubTreeCommand extends CommandTreeBase {
    private final CommandBase parent;

    public SubTreeCommand(CommandBase parent) {
        super();
        this.parent = parent;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return parent.getRequiredPermissionLevel();
    }

    @Override
    public boolean checkPermission(@NotNull MinecraftServer server, @NotNull ICommandSender sender) {
        return parent.checkPermission(server, sender);
    }
}
