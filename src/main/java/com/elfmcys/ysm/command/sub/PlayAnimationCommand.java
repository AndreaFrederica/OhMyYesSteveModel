package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.command.RootCommand;
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

public class PlayAnimationCommand extends CommandBase {
    private static final String STOP = "stop";

    public PlayAnimationCommand() {
    }

    @NotNull
    @Override
    public String getName() {
        return "play";
    }

    @NotNull
    @Override
    public String getUsage(@NotNull ICommandSender sender) {
        return "commands.yes_steve_model.ysm.play.usage";
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
        if (args.length == 2) {
            playAnimation(server, sender, args[0], args[1]);
            return;
        }
        throw new WrongUsageException(this.getUsage(sender));
    }

    @NotNull
    @Override
    public List<String> getTabCompletions(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                                          String[] args, @Nullable BlockPos targetPos) {
        return switch (args.length) {
            case 0 -> getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
            case 1 -> getListOfStringsMatchingLastWord(args, RootCommand.allAnimations());
            default -> List.of();
        };
    }

    private static void playAnimation(MinecraftServer server, ICommandSender sender, String target, String animation) throws CommandException {
        Collection<EntityPlayerMP> targets = getPlayers(server, sender, target);
        targets.forEach(player -> {
            var cap = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
            if (cap == null) return;
            if (STOP.equals(animation)) {
                cap.stopAnimation(player);
            } else {
                cap.playAnimation(player, animation);
            }
        });
    }
}
