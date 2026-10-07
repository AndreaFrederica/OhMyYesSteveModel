package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fml.common.Loader;
import org.jetbrains.annotations.NotNull;

public class PingCommand extends CommandBase {
    public PingCommand() {
    }

    @NotNull
    @Override
    public String getName() {
        return "ping";
    }

    @NotNull
    @Override
    public String getUsage(@NotNull ICommandSender sender) {
        return "commands.yes_steve_model.ysm.ping.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
        if (args.length == 0) {
            ping(sender);
            return;
        }
        throw new WrongUsageException(this.getUsage(sender));
    }

    private static void ping(ICommandSender sender) throws CommandException {
        var player = getCommandSenderAsPlayer(sender);
        player.sendMessage(new TextComponentTranslation("message.yes_steve_model.client.ping_result", Loader.instance().getIndexedModList().get(YesSteveModel.MOD_ID).getVersion()));
        SessionProtocolHandler.sendServerHello(player);
    }
}
