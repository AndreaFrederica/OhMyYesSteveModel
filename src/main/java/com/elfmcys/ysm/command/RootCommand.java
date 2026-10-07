package com.elfmcys.ysm.command;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.command.sub.*;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.google.common.collect.Sets;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.server.command.CommandTreeBase;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 失败操作用 sender.sendMessage（后台看不到）
// 成功操作用 notifyCommandListener（后台可见）
public class RootCommand extends CommandTreeBase {
    public RootCommand() {
        this.addSubcommand(new ModelCommand());
        this.addSubcommand(new AuthCommand());
        this.addSubcommand(new ExportCommand());
        this.addSubcommand(new PlayAnimationCommand());
        this.addSubcommand(new MolangCommand());
        this.addSubcommand(new PingCommand());
    }

    @NotNull
    @Override
    public String getName() {
        return "ysm";
    }

    @NotNull
    @Override
    public String getUsage(@NotNull ICommandSender sender) {
        return "commands.yes_steve_model.ysm.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    public static class Placeholder extends CommandBase {
        public Placeholder() {
        }

        @NotNull
        @Override
        public String getName() {
            return "ysm";
        }

        @NotNull
        @Override
        public String getUsage(@NotNull ICommandSender sender) {
            return "commands.yes_steve_model.ysm_placeholder.usage";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public void execute(@NotNull MinecraftServer server, ICommandSender sender, String @NotNull [] args) throws CommandException {
            if (sender.getCommandSenderEntity() instanceof EntityPlayerMP) {
                sender.sendMessage(YesSteveModel.getUnavailableMessage());
            } else {
                sender.sendMessage(new TextComponentString(YesSteveModel.getUnavailableMessageString()));
            }
        }
    }

    public static List<String> allBooleans() {
        return List.of("true", "false");
    }

    public static List<String> allModels() {
        //noinspection UnnecessaryLocalVariable
        var paths = ServerModelService.current().flatMap(ServerModelService::catalog)
                .map(snapshot -> snapshot.models().values().stream()
                        .map(handle -> handle.location().path().value()).distinct()
                        .toList()).orElse(List.of());
        return paths;
    }

    public static List<String> allAnimations() {
        if (FMLCommonHandler.instance().getSide() == Side.SERVER) {
            // Fixme: 应该为服务器后台也添加提示功能
            return List.of();
        } else {
            var main = ClientModelService.instance().defaultRenderTarget().playerResources().animations();
            Set<String> animations = Sets.newHashSet();
            animations.addAll(main.keySet().stream().toList());
            animations.add("stop");
            return new ArrayList<>(animations);
        }
    }

    public static List<String> allTextures(String modelPath) {
        var model = ServerModelService.current().flatMap(ServerModelService::catalog)
                .flatMap(snapshot -> snapshot.findPath(modelPath));
        if (model.isPresent()) {
            List<String> textures = model.get().representation().view().getPlayer().getTextureNames().stream()
                    .sorted()
                    .toList();
            textures.add(0, "-");
            return textures;
        }
        return List.of();
    }

    // FIXME：旧版命令分词不含空格，需要转义
    private static String filterSuggestionStr(String str) {
        return str;
        /*
        if (str.chars().allMatch(c -> StringReader.isAllowedInUnquotedString((char) c))) {
            return str;
        }
        return String.format("\"%s\"", str.replace("\"", "\\\"").replace("'", "\\'"));
        */
    }
}
