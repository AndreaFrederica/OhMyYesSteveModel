package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.capability.AuthModelsCapabilityProvider;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.command.RootCommand;
import com.elfmcys.ysm.command.SubCommand;
import com.elfmcys.ysm.model.catalog.source.AccessPolicy;
import com.elfmcys.ysm.model.domain.ModelScanWarning;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.session.server.state.Selection;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
import com.elfmcys.ysm.util.CommandUtil;
import net.minecraft.command.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.server.command.CommandTreeBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ModelCommand extends CommandTreeBase {
    public ModelCommand() {
        this.addSubcommand(new ReloadCommand(this));
        this.addSubcommand(new ErrorsCommand(this));
        this.addSubcommand(new DisableCommand(this));
        this.addSubcommand(new SetCommand(this));
    }

    @NotNull
    @Override
    public String getName() {
        return "model";
    }

    @NotNull
    @Override
    public String getUsage(@NotNull ICommandSender sender) {
        return "commands.yes_steve_model.ysm.model.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public boolean checkPermission(@NotNull MinecraftServer server, @NotNull ICommandSender sender) {
        return super.checkPermission(server, sender) || CommandUtil.isLocalPlayer(sender.getCommandSenderEntity());
    }

    private static class ReloadCommand extends SubCommand {
        public ReloadCommand(CommandBase parent) {
            super(parent);
        }

        @NotNull
        @Override
        public String getName() {
            return "reload";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.model.reload.usage";
        }

        @Override
        public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
            if (args.length != 0) {
                throw new WrongUsageException(this.getUsage(sender));
            }
            reload(server, sender, this);
        }
    }

    private static class ErrorsCommand extends SubCommand {
        public ErrorsCommand(CommandBase parent) {
            super(parent);
        }

        @NotNull
        @Override
        public String getName() {
            return "errors";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.model.errors.usage";
        }

        @Override
        public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
            if (args.length != 0) {
                throw new WrongUsageException(this.getUsage(sender));
            }
            errors(sender);
        }
    }

    private static class DisableCommand extends SubCommand {
        public DisableCommand(CommandBase parent) {
            super(parent);
        }

        @NotNull
        @Override
        public String getName() {
            return "disable";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.model.disable.usage";
        }

        @Override
        public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
            if (args.length != 2) {
                throw new WrongUsageException(this.getUsage(sender));
            }
            disable(server, sender, args[0], args[1]);
        }

        @NotNull
        @Override
        public List<String> getTabCompletions(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                                              String[] args, @Nullable BlockPos targetPos) {
            return switch (args.length) {
                case 1 -> getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
                case 2 -> getListOfStringsMatchingLastWord(args, RootCommand.allBooleans());
                default -> List.of();
            };
        }
    }

    private static class SetCommand extends SubCommand {
        public SetCommand(CommandBase parent) {
            super(parent);
        }

        @NotNull
        @Override
        public String getName() {
            return "set";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.model.set.usage";
        }

        @Override
        public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
            switch (args.length) {
                case 3 -> set(server, sender, args[0], args[1], args[2], false);
                case 4 -> set(server, sender, args[0], args[1], args[2], parseBoolean(args[3]));
                default -> throw new WrongUsageException(this.getUsage(sender));
            }
        }

        @NotNull
        @Override
        public List<String> getTabCompletions(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                                              String[] args, @Nullable BlockPos targetPos) {
            return switch (args.length) {
                case 1 -> getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
                case 2 -> getListOfStringsMatchingLastWord(args, RootCommand.allModels());
                case 3 -> getListOfStringsMatchingLastWord(args, RootCommand.allTextures(args[1]));
                case 4 -> getListOfStringsMatchingLastWord(args, RootCommand.allBooleans());
                default -> List.of();
            };
        }
    }

    private static void set(MinecraftServer server, ICommandSender sender, String path, String texture, String targets, boolean ignoreAuth)
            throws CommandException {
        var catalog = ServerModelService.instance().catalog().orElseThrow();
        var model = catalog.findPath(path);
        if (model.isEmpty()) {
            CommandUtil.sendFailure(sender, new TextComponentString("Unknown or ambiguous model path: " + path));
            return;
        }
        var handle = model.get();
        if ("-".equals(texture)) {
            var requested = handle.view().getMetadata().getSettings().defaultTexture().orElse("");
            texture = handle.view().getPlayer().getTextureNames().contains(requested)
                    ? requested : handle.view().getPlayer().getTextureNames().stream().sorted().findFirst().orElse("");
        }
        if (!handle.view().getPlayer().getTextureNames().contains(texture)) {
            CommandUtil.sendFailure(sender, new TextComponentString("Unknown texture for " + path + ": " + texture));
            return;
        }

        var hash = handle.representation().modelId();
        var selectedTexture = texture;
        for (var player : getPlayers(server, sender, targets)) {
            var selection = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
            if (selection != null) {
                var auth = player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP, null);
                if (auth != null) {
                    if (!ignoreAuth && handle.location().rootKind().accessPolicy()
                            == AccessPolicy.SESSION_AUTHORIZED
                            && !auth.containModel(hash)) {
                        CommandUtil.sendFailure(sender, new TextComponentString(
                                player.getName() + " is not authorized for " + path));
                        return;
                    }
                    if (!SessionProtocolHandler.applyCommandSelection(player, selection,
                            new Selection.Model(hash, selectedTexture), ignoreAuth)) {
                        CommandUtil.sendFailure(sender, new TextComponentString(
                                player.getName() + " rejected the model selection"));
                        return;
                    }
                }
            }
        }
    }

    private static void reload(MinecraftServer server, ICommandSender sender, ICommand command) {
        notifyCommandListener(sender, command, "Reloading model catalog...");
        ServerModelService.instance().reload().thenAccept(result -> {
            CommandUtil.sendAsyncFeedback(server, sender, command, true, result.success()
                    ? "Model catalog reloaded: " + result.modelCount() + " model(s), "
                    + result.errorCount() + " scan error(s)"
                    : "Model catalog reload failed: " + result.message());
            if (result.success() && result.warningCount() > 0) {
                for (var kind : ModelScanWarning.Kind.values()) {
                    var count = result.warnings().stream()
                            .filter(warning -> warning.kind() == kind)
                            .mapToInt(ModelScanWarning::occurrences)
                            .sum();
                    if (count > 0) {
                        var key = switch (kind) {
                            case UNKNOWN_AUDIO -> "commands.ysm.model.reload.unknown_audio";
                            case INVALID_AUDIO -> "commands.ysm.model.reload.invalid_audio";
                        };
                        CommandUtil.sendAsyncFeedback(server, sender, command, true, key, count);
                    }
                }
            }
        });
    }

    private static void errors(ICommandSender sender) {
        var errors = ServerModelService.instance().lastReport().errors();
        if (errors.isEmpty()) {
            sender.sendMessage(new TextComponentString("The last model scan had no errors."));
            return;
        }
        sender.sendMessage(new TextComponentString("Model scan errors: " + errors.size()));
        for (var error : errors) {
            var text = "[" + error.rootKind() + "] " + error.source() + ": " + error.message();
            if (!error.detail().isBlank()) {
                text += "\n" + error.detail();
            }
            var output = text;
            sender.sendMessage(new TextComponentString(output));
        }
    }

    private static void disable(MinecraftServer server, ICommandSender sender, String targets, String value) throws CommandException {
        var disabled = parseBoolean(value);
        for (var player : getPlayers(server, sender, targets)) {
            var capability = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
            if (capability != null) capability.setDisabled(disabled);
        }
    }
}
