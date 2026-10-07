package com.elfmcys.ysm.client.command.sub;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.animation.molang.CustomMolangParser;
import com.elfmcys.ysm.client.command.ClientRootCommand;
import com.elfmcys.ysm.client.event.RenderDebugAnimationScreenEvent;
import com.elfmcys.ysm.command.SubCommand;
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

import java.util.List;

public class SimpleWatchCommand extends CommandTreeBase {
    /**
     * 简化版本
     * ysmclient watch var <exp> 等效于 ysmclient molang watch add post <exp_name> <exp>
     */
    public SimpleWatchCommand() {
        this.addSubcommand(new VarCommand(this));
        this.addSubcommand(new StateCommand(this));
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
        return "commands.yes_steve_model.ysmclient.watch.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    private static class VarCommand extends SubCommand {
        public VarCommand(CommandBase parent) {
            super(parent);
        }

        @NotNull
        @Override
        public String getName() {
            return "var";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.ysmclient.watch.var.usage";
        }

        @Override
        public void execute(@Nullable MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
            if (args.length != 1) {
                throw new WrongUsageException(this.getUsage(sender));
            }
            addExpression(sender, args[0]);
        }

        @NotNull
        @Override
        public List<String> getTabCompletions(@Nullable MinecraftServer server, @NotNull ICommandSender sender,
                                              String[] args, @Nullable BlockPos targetPos) {
            return args.length == 1
                    ? getListOfStringsMatchingLastWord(args, ClientRootCommand.allVars()) : List.of();
        }
    }

    private static class StateCommand extends SubCommand {
        public StateCommand(CommandBase parent) {
            super(parent);
        }

        @NotNull
        @Override
        public String getName() {
            return "state";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.ysmclient.watch.state.usage";
        }

        @Override
        public void execute(@Nullable MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
            if (args.length != 1) {
                throw new WrongUsageException(this.getUsage(sender));
            }
            addControllerState(args[0]);
        }

        @NotNull
        @Override
        public List<String> getTabCompletions(@Nullable MinecraftServer server, @NotNull ICommandSender sender,
                                              String[] args, @Nullable BlockPos targetPos) {
            return args.length == 1
                    ? getListOfStringsMatchingLastWord(args, ClientRootCommand.allControllers()) : List.of();
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
            return "commands.yes_steve_model.ysmclient.watch.clear.usage";
        }

        @Override
        public void execute(@Nullable MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] args) throws CommandException {
            if (args.length != 0) {
                throw new WrongUsageException(this.getUsage(sender));
            }
            clearExpression();
        }
    }

    private static void addExpression(ICommandSender sender, String exp) {
        if (!isClientReady()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        IValue value;
        try {
            value = CustomMolangParser.parseSingleExpressionUnsafe(exp);
        } catch (ParseException e) {
            CommandUtil.sendFailure(sender, new TextComponentTranslation("message.yes_steve_model.model.debug_animation.parser_error", e.getMessage()));
            return;
        }
        mc.addScheduledTask(() -> {
            var cap = mc.player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
            if (cap == null) return;
            RenderDebugAnimationScreenEvent.getDebugInfo().add(DebugInfo.Phase.POST_ANIMATION, exp, value);
            // 强制打开调试界面
            if (!RenderDebugAnimationScreenEvent.isEnabled()) {
                RenderDebugAnimationScreenEvent.enableForLocalPlayer();
            }
        });
    }

    private static void clearExpression() {
        if (!isClientReady()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            var cap = mc.player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
            if (cap != null) RenderDebugAnimationScreenEvent.getDebugInfo().clear();
        });
        RenderDebugAnimationScreenEvent.clearDebugController();
    }

    private static void addControllerState(String controllerName) {
        if (!isClientReady()) {
            return;
        }
        // 强制打开调试界面
        RenderDebugAnimationScreenEvent.addDebugController(controllerName);
        if (!RenderDebugAnimationScreenEvent.isEnabled()) {
            RenderDebugAnimationScreenEvent.enableForLocalPlayer();
        }
    }

    @SuppressWarnings("ConstantValue")
    private static boolean isClientReady() {
        return Minecraft.getMinecraft() != null && Minecraft.getMinecraft().player != null;
    }
}
