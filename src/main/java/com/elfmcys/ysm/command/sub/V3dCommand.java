package com.elfmcys.ysm.command.sub;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.v3d.V3dCache;
import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.util.CommandUtil;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.Path;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Server/integrated-server adapter; all V3D interpretation and IO live in ysmlib. */
public final class V3dCommand {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 1, 30,
            TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "YSM V3D Export");
                thread.setDaemon(true);
                return thread;
            });

    private V3dCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        var root = Commands.literal("v3d").requires(source -> CommandUtil.hasPermission(source, 2));
        root.executes(context -> {
            context.getSource().sendSuccess(() -> Component.translatable("commands.yes_steve_model.v3d.usage"), false);
            return 1;
        });
        for (String operation : new String[]{"export", "validate", "restore"}) {
            root.then(Commands.literal(operation)
                    .then(Commands.argument("path", StringArgumentType.string())
                            .executes(context -> submit(context, operation))));
        }
        return root;
    }

    private static int submit(CommandContext<CommandSourceStack> context, String operation) {
        var source = context.getSource();
        var server = source.getServer();
        String relative = StringArgumentType.getString(context, "path");
        Path models = AssetPaths.customModelsRoot();
        Path exports = AssetPaths.gameModelsRoot().resolve("export");
        try {
            WORKER.execute(() -> {
                try {
                    Path result = execute(operation, relative, models, exports);
                    if (server.isRunning()) server.execute(() -> {
                        if (currentSource(source)) source.sendSuccess(() -> Component.translatable(
                                "commands.yes_steve_model.v3d.success", operation, result.toString()), false);
                    });
                } catch (Exception failure) {
                    YesSteveModel.LOGGER.warn("V3D {} failed for {}", operation, relative, failure);
                    if (server.isRunning()) server.execute(() -> {
                        if (currentSource(source)) source.sendFailure(Component.translatable(
                                "commands.yes_steve_model.v3d.failure", failure.getMessage()));
                    });
                }
            });
        } catch (RejectedExecutionException busy) {
            source.sendFailure(Component.translatable("commands.yes_steve_model.v3d.busy"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.yes_steve_model.v3d.started", operation), false);
        return 1;
    }

    private static boolean currentSource(CommandSourceStack source) {
        return !(source.getEntity() instanceof ServerPlayer player)
                || source.getServer().getPlayerList().getPlayer(player.getUUID()) == player;
    }

    static Path execute(String operation, String relative, Path models, Path exports) throws Exception {
        Path workspaces = exports.resolve("v3d");
        return switch (operation) {
            case "export" -> YsmRuntime.v3d().materialize(
                    V3dCommandPaths.existing(models, relative, false),
                    V3dCommandPaths.outputDirectory(workspaces));
            case "validate" -> {
                Path workspace = V3dCommandPaths.existing(workspaces, relative, true);
                V3dCache.validate(workspace);
                yield workspace;
            }
            case "restore" -> {
                Path workspace = V3dCommandPaths.existing(workspaces, relative, true);
                String name = workspace.getFileName().toString().replaceFirst("\\.v3d$", "");
                Path output = V3dCommandPaths.outputDirectory(exports.resolve("restored")).resolve(name + ".ysm");
                V3dCache.restoreOriginal(workspace, output);
                yield output;
            }
            default -> throw new IllegalArgumentException("Unknown V3D operation: " + operation);
        };
    }
}
