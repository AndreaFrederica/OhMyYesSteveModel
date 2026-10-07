package com.elfmcys.ysm.command.sub;

import com.elfmcys.ysm.command.RootCommand;
import com.elfmcys.ysm.model.service.ModelExportService;
import com.elfmcys.ysm.util.CommandUtil;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class ExportCommand extends CommandBase {
    public ExportCommand() {
    }

    @NotNull
    @Override
    public String getName() {
        return "export";
    }

    @NotNull
    @Override
    public String getUsage(@NotNull ICommandSender sender) {
        return "commands.yes_steve_model.ysm.export.usage";
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
        if (args.length == 1) {
            export(server, sender, args[0], null);
            return;
        }
        if (args.length >= 2) {
            String extra = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            export(server, sender, args[0], extra);
            return;
        }
        throw new WrongUsageException(this.getUsage(sender));
    }

    @NotNull
    @Override
    public List<String> getTabCompletions(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                                          String[] args, @Nullable BlockPos targetPos) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, RootCommand.allModels()) : List.of();
    }

    private static void export(MinecraftServer server, ICommandSender sender, String model, @Nullable String extra) {
        CommandUtil.sendFailure(sender, new TextComponentString("当前模型格式尚未稳定，禁止导出"));
//        ModelExportService.export(model, extra)
//                .whenComplete((output, failure) -> server.addScheduledTask(() -> {
//                    if (failure == null) {
//                        sender.sendMessage(new TextComponentTranslation(
//                                "commands.yes_steve_model.export.success", output));
//                    } else {
//                        var cause = failure.getCause() == null ? failure : failure.getCause();
//                        CommandUtil.sendFailure(sender, new TextComponentTranslation(
//                                "commands.yes_steve_model.export.failure",
//                                Objects.requireNonNullElse(
//                                        cause.getMessage(), cause.getClass().getSimpleName())));
//                    }
//                }));
    }
}
