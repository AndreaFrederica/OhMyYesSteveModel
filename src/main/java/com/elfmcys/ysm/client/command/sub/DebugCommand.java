package com.elfmcys.ysm.client.command.sub;

import com.elfmcys.ysm.client.command.ClientRootCommand;
import com.elfmcys.ysm.client.event.RenderDebugAnimationScreenEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.command.SelectorHandlerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class DebugCommand extends CommandBase {
    public DebugCommand() {
    }

    @NotNull
    @Override
    public String getName() {
        return "debug";
    }

    @NotNull
    @Override
    public String getUsage(@NotNull ICommandSender sender) {
        return "commands.yes_steve_model.ysmclient.debug.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(@Nullable MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
        if (args.length == 1) {
            enableDebugging(sender, args[0]);
            return;
        }
        throw new WrongUsageException(this.getUsage(sender));
    }

    @NotNull
    @Override
    public List<String> getTabCompletions(@Nullable MinecraftServer server, @NotNull ICommandSender sender,
                                          String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            var world = Minecraft.getMinecraft().world;
            if (world != null) {
                List<String> candidates = new ArrayList<>(SelectorHandlerManager.registeringMods.keySet());
                for (var player : world.playerEntities) {
                    candidates.add(player.getName());
                }
                getListOfStringsMatchingLastWord(args, candidates);
            }
        }
        return List.of();
    }

    private static void enableDebugging(ICommandSender sender, String target) throws CommandException {
        Minecraft mc = Minecraft.getMinecraft();
        var world = mc.world;
        var entity = ClientRootCommand.getClientEntity(world, sender, target);
        var clientEntity = world.getEntityByID(entity.getEntityId());
        RenderDebugAnimationScreenEvent.enable(clientEntity);
    }
}
