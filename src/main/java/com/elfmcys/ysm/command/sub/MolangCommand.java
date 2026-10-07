package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.proto.network.EntityRef;
import com.elfmcys.ysm.proto.network.ExecuteMolangEvent;
import com.elfmcys.ysm.util.CommandUtil;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

public class MolangCommand extends CommandBase {
    private static final String EXECUTE_NAME = "execute";

    public MolangCommand() {
    }

    @NotNull
    @Override
    public String getName() {
        return "molang";
    }

    @NotNull
    @Override
    public String getUsage(@NotNull ICommandSender sender) {
        return "commands.yes_steve_model.ysm.molang.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public boolean checkPermission(@NotNull MinecraftServer server, @NotNull ICommandSender sender) {
        return super.checkPermission(server, sender) || CommandUtil.isLocalPlayer(sender.getCommandSenderEntity());
    }

    @Override
    public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
        if (args.length == 3 && EXECUTE_NAME.equals(args[0])) {
            executeMolang(server, sender, args[1], args[2]);
            return;
        }
        throw new WrongUsageException(this.getUsage(sender));
    }

    @NotNull
    @Override
    public List<String> getTabCompletions(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                                          String[] args, @Nullable BlockPos targetPos) {
        return switch (args.length) {
            case 1 -> getListOfStringsMatchingLastWord(args, EXECUTE_NAME);
            case 2 ->
                    EXECUTE_NAME.equalsIgnoreCase(args[0]) ? getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames()) : List.of();
            default -> List.of();
        };
    }

    private static void executeMolang(MinecraftServer server, ICommandSender sender, String target, String molangExp) throws CommandException {
        Collection<EntityPlayerMP> targets = getPlayers(server, sender, target);
        executeMolangOnPlayer(molangExp, targets);
    }

    private static void executeMolangOnPlayer(String molangExp, Collection<EntityPlayerMP> players) {
        var packet = ExecuteMolangEvent.newBuilder().setExpression(molangExp);
        players.forEach(player -> packet.addTargets(EntityRef.newBuilder()
                .setEntityId(player.getEntityId()).build()));
        NetworkHandler.broadcastToAllPlayers(packet.build());
    }
}
