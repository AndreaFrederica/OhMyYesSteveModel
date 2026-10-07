package com.elfmcys.ysm.client.command.sub;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.animation.molang.CustomMolangParser;
import com.elfmcys.ysm.client.command.ClientRootCommand;
import com.elfmcys.ysm.client.event.RenderDebugAnimationScreenEvent;
import com.elfmcys.ysm.command.SubCommand;
import com.elfmcys.ysm.command.SubTreeCommand;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.core.processor.DebugInfo;
import com.elfmcys.ysm.molang.parser.ParseException;
import com.elfmcys.ysm.util.CommandUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.server.command.CommandTreeBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

public class MolangCommand extends CommandTreeBase {
    public MolangCommand() {
        this.addSubcommand(new WatchCommand(this));
        this.addSubcommand(new ExecuteCommand(this));
    }

    @NotNull
    @Override
    public String getName() {
        return "molang";
    }

    @NotNull
    @Override
    public String getUsage(@NotNull ICommandSender sender) {
        return "commands.yes_steve_model.ysmclient.molang.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    private static class WatchCommand extends SubTreeCommand {
        private WatchCommand(CommandBase parent) {
            super(parent);
            this.addSubcommand(new AddCommand(this));
            this.addSubcommand(new RemoveCommand(this));
            this.addSubcommand(new ClearCommand(this));
        }

        @NotNull
        @Override
        public String getName() {
            return "watch";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.ysmclient.molang.watch.usage";
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
                return "commands.yes_steve_model.ysmclient.molang.watch.add.usage";
            }

            @Override
            public void execute(@Nullable MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
                if (args.length < 3) {
                    throw new WrongUsageException(this.getUsage(sender));
                }
                DebugInfo.Phase phase = switch (args[0]) {
                    case "pre" -> DebugInfo.Phase.PRE_ANIMATION;
                    case "post" -> DebugInfo.Phase.POST_ANIMATION;
                    default -> throw new WrongUsageException(this.getUsage(sender));
                };
                addExpression(sender, args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)), phase);
            }

            @NotNull
            @Override
            public List<String> getTabCompletions(@Nullable MinecraftServer server, @NotNull ICommandSender sender,
                                                  String[] args, @Nullable BlockPos targetPos) {
                return switch (args.length) {
                    case 1 -> getListOfStringsMatchingLastWord(args, "pre", "post");
                    case 3 -> switch (args[0].toLowerCase()) {
                        case "pre", "post" -> getListOfStringsMatchingLastWord(args, ClientRootCommand.allVars());
                        default -> List.of();
                    };
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
                return "commands.yes_steve_model.ysmclient.molang.watch.remove.usage";
            }

            @Override
            public void execute(@Nullable MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
                if (args.length != 1) {
                    throw new WrongUsageException(this.getUsage(sender));
                }
                removeExpression(args[0]);
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
                return "commands.yes_steve_model.ysmclient.molang.watch.clear.usage";
            }

            @Override
            public void execute(@Nullable MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
                if (args.length != 0) {
                    throw new WrongUsageException(this.getUsage(sender));
                }
                clearExpression();
            }
        }
    }

    private static class ExecuteCommand extends SubCommand {
        public ExecuteCommand(CommandBase parent) {
            super(parent);
        }

        @NotNull
        @Override
        public String getName() {
            return "execute";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.ysmclient.molang.execute.usage";
        }

        @Override
        public void execute(@Nullable MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
            if (args.length < 1) {
                throw new WrongUsageException(this.getUsage(sender));
            }
            executeMolang(sender, String.join(" ", args));
        }

        @NotNull
        @Override
        public List<String> getTabCompletions(@Nullable MinecraftServer server, @NotNull ICommandSender sender,
                                              String[] args, @Nullable BlockPos targetPos) {
            return args.length == 1 ? getListOfStringsMatchingLastWord(args, ClientRootCommand.allVars()) : List.of();
        }
    }

    private static void addExpression(ICommandSender sender, String expName, String exp, DebugInfo.Phase phase) {
        if (!isClientReady()) {
            return;
        }
        IValue value;
        try {
            value = CustomMolangParser.parseSingleExpressionUnsafe(exp);
        } catch (ParseException e) {
            CommandUtil.sendFailure(sender, new TextComponentTranslation("message.yes_steve_model.model.debug_animation.parser_error", e.getMessage()));
            return;
        }
        Minecraft.getMinecraft().addScheduledTask(() -> {
            var cap = Minecraft.getMinecraft().player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
            if (cap != null) RenderDebugAnimationScreenEvent.getDebugInfo().add(phase, expName, value);
        });
    }

    private static void removeExpression(String expName) {
        if (!isClientReady()) {
            return;
        }
        Minecraft.getMinecraft().addScheduledTask(() -> {
            var cap = Minecraft.getMinecraft().player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
            if (cap != null) RenderDebugAnimationScreenEvent.getDebugInfo().remove(expName);
        });
    }

    private static void clearExpression() {
        if (!isClientReady()) {
            return;
        }
        Minecraft.getMinecraft().addScheduledTask(() -> {
            var cap = Minecraft.getMinecraft().player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
            if (cap != null) RenderDebugAnimationScreenEvent.getDebugInfo().clear();
        });
    }

    private static void executeMolang(ICommandSender sender, String exp) {
        if (!isClientReady()) {
            return;
        }
        IValue value;
        try {
            value = CustomMolangParser.parseSingleExpressionUnsafe(exp);
        } catch (ParseException e) {
            CommandUtil.sendFailure(sender, new TextComponentTranslation("message.yes_steve_model.model.debug_animation.parser_error", e.getMessage()));
            return;
        }

        var target = RenderDebugAnimationScreenEvent.getTarget();
        if (target == null) {
            target = Minecraft.getMinecraft().player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
        }
        if (target != null) {
            target.executeMolangExp(value, true, false, result -> {
                Minecraft.getMinecraft().player.sendMessage(new TextComponentTranslation("message.yes_steve_model.model.debug_animation.result", result));
            });
        }
    }

    @SuppressWarnings("ConstantValue")
    private static boolean isClientReady() {
        return Minecraft.getMinecraft() != null && Minecraft.getMinecraft().player != null;
    }
}
