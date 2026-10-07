package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.capability.AuthModelsCapabilityProvider;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.ModelSelectionService;
import com.elfmcys.ysm.command.RootCommand;
import com.elfmcys.ysm.command.SubCommand;
import com.elfmcys.ysm.model.catalog.source.AccessPolicy;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
import com.elfmcys.ysm.util.CommandUtil;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.server.command.CommandTreeBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class AuthCommand extends CommandTreeBase {
    public AuthCommand() {
        this.addSubcommand(new AddCommand(this));
        this.addSubcommand(new RemoveCommand(this));
        this.addSubcommand(new AllCommand(this));
        this.addSubcommand(new ClearCommand(this));
    }

    @NotNull
    @Override
    public String getName() {
        return "auth";
    }

    @NotNull
    @Override
    public String getUsage(@NotNull ICommandSender sender) {
        return "commands.yes_steve_model.ysm.auth.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public boolean checkPermission(@NotNull MinecraftServer server, @NotNull ICommandSender sender) {
        return super.checkPermission(server, sender) || CommandUtil.isLocalPlayer(sender.getCommandSenderEntity());
    }

    private static class AddCommand extends SubCommand {
        public AddCommand(CommandBase parent) {
            super(parent);
        }

        @NotNull
        @Override
        public String getName() {
            return "add";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.auth.add.usage";
        }

        @Override
        public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
            if (args.length != 2) {
                throw new WrongUsageException(this.getUsage(sender));
            }
            change(server, sender, args[0], args[1], true);
        }

        @NotNull
        @Override
        public List<String> getTabCompletions(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                                              String[] args, @Nullable BlockPos targetPos) {
            return switch (args.length) {
                case 1 -> getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
                case 2 -> getListOfStringsMatchingLastWord(args, RootCommand.allModels());
                default -> List.of();
            };
        }
    }

    private static class RemoveCommand extends SubCommand {
        public RemoveCommand(CommandBase parent) {
            super(parent);
        }

        @NotNull
        @Override
        public String getName() {
            return "remove";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.auth.remove.usage";
        }

        @Override
        public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
            if (args.length != 2) {
                throw new WrongUsageException(this.getUsage(sender));
            }
            change(server, sender, args[0], args[1], false);
        }

        @NotNull
        @Override
        public List<String> getTabCompletions(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                                              String[] args, @Nullable BlockPos targetPos) {
            return switch (args.length) {
                case 1 -> getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
                case 2 -> getListOfStringsMatchingLastWord(args, RootCommand.allModels());
                default -> List.of();
            };
        }
    }

    private static class AllCommand extends SubCommand {
        public AllCommand(CommandBase parent) {
            super(parent);
        }

        @NotNull
        @Override
        public String getName() {
            return "all";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.auth.all.usage";
        }

        @Override
        public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
            if (args.length != 1) {
                throw new WrongUsageException(this.getUsage(sender));
            }
            all(server, sender, args[0]);
        }

        @NotNull
        @Override
        public List<String> getTabCompletions(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                                              String[] args, @Nullable BlockPos targetPos) {
            return args.length == 1 ? getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames()) : List.of();
        }
    }

    private static class ClearCommand extends SubCommand {
        public ClearCommand(CommandBase parent) {
            super(parent);
        }

        @NotNull
        @Override
        public String getName() {
            return "clear";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.auth.clear.usage";
        }

        @Override
        public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
            if (args.length != 1) {
                throw new WrongUsageException(this.getUsage(sender));
            }
            clear(server, sender, args[0]);
        }

        @NotNull
        @Override
        public List<String> getTabCompletions(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                                              String[] args, @Nullable BlockPos targetPos) {
            return args.length == 1 ? getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames()) : List.of();
        }
    }

    private static void change(MinecraftServer server, ICommandSender sender, String targets, String path, boolean add)
            throws CommandException {
        var snapshot = ServerModelService.instance().catalog().orElseThrow();
        var model = snapshot.findPath(path);
        if (model.isEmpty()) {
            CommandUtil.sendFailure(sender, new TextComponentString("Unknown or ambiguous model path: " + path));
            return;
        }
        var hash = model.get().representation().modelId();
        for (var player : getPlayers(server, sender, targets)) {
            var capability = player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP, null);
            if (capability != null) {
                if (add) {
                    capability.addModel(hash);
                } else {
                    capability.removeModel(hash);
                    var selection = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
                    if (selection != null) {
                        if (hash.equals(selection.getModelId())
                                && !selection.ignoresGrantsFor(hash)) {
                            ModelSelectionService.selectDefault(selection, snapshot);
                        }
                    }
                }
                SessionProtocolHandler.refreshGrants(player);
            }
        }
    }

    private static void all(MinecraftServer server, ICommandSender sender, String targets) throws CommandException {
        var hashes = ServerModelService.instance().catalog().orElseThrow().models().keySet();
        for (var player : getPlayers(server, sender, targets)) {
            var capability = player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP, null);
            if (capability != null) {
                hashes.forEach(capability::addModel);
                SessionProtocolHandler.refreshGrants(player);
            }
        }
    }

    private static void clear(MinecraftServer server, ICommandSender sender, String targets) throws CommandException {
        var snapshot = ServerModelService.instance().catalog().orElseThrow();
        for (var player : getPlayers(server, sender, targets)) {
            var capability = player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP, null);
            if (capability != null) {
                capability.clear();
                var selection = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
                if (selection != null) {
                    var selected = selection.getModelId();
                    if (selected != null && snapshot.find(selected)
                            .map(handle -> handle.location().rootKind().accessPolicy()
                                    == AccessPolicy.SESSION_AUTHORIZED)
                            .orElse(false)
                            && !selection.ignoresGrantsFor(selected)) {
                        ModelSelectionService.selectDefault(selection, snapshot);
                    }
                }
                SessionProtocolHandler.refreshGrants(player);
            }
        }
    }
}
