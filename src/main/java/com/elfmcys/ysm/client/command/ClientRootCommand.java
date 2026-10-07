package com.elfmcys.ysm.client.command;

import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.animation.molang.CustomMolangParser;
import com.elfmcys.ysm.client.animation.molang.roaming.LocalRoamingStruct;
import com.elfmcys.ysm.client.command.sub.DebugCommand;
import com.elfmcys.ysm.client.command.sub.MolangCommand;
import com.elfmcys.ysm.client.command.sub.SimpleWatchCommand;
import com.elfmcys.ysm.client.entity.CustomEntity;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.entity.IRoamingEntity;
import com.elfmcys.ysm.client.event.RenderDebugAnimationScreenEvent;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import com.elfmcys.ysm.geckolib3.core.molang.binding.ContextBinding;
import com.elfmcys.ysm.geckolib3.core.molang.util.StringPool;
import com.elfmcys.ysm.util.CommandUtil;
import com.google.common.collect.Sets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.command.CommandException;
import net.minecraft.command.EntityNotFoundException;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.server.command.CommandTreeBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class ClientRootCommand extends CommandTreeBase {
    public ClientRootCommand() {
        this.addSubcommand(new MolangCommand());
        this.addSubcommand(new SimpleWatchCommand());
        this.addSubcommand(new DebugCommand());
    }

    @NotNull
    @Override
    public String getName() {
        return "ysmclient";
    }

    @NotNull
    @Override
    public String getUsage(@NotNull ICommandSender sender) {
        return "commands.yes_steve_model.ysmclient.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    // 仅客户端时 MinecraftServer 可为 null
    @Override
    public boolean checkPermission(@Nullable MinecraftServer server, @NotNull ICommandSender sender) {
        return CommandUtil.isLocalPlayer(sender.getCommandSenderEntity());
    }

    public static List<String> allVars() {
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            var cap = getTarget();
            if (cap != null) {
                // v 变量
                Set<String> vars = Sets.newHashSet();
                cap.getAnimationProcessor().visitScopedVariableNames(name -> {
                    vars.add(String.format("v.%s", name));
                });

                // v.roaming 变量
                if (cap instanceof IRoamingEntity roaming && roaming.getRoamingStruct() instanceof LocalRoamingStruct struct) {
                    struct.visitNames(s -> {
                        Object object = struct.getProperty(StringPool.getName(s));
                        if (object != null) {
                            vars.add(String.format("v.roaming.%s", s));
                        }
                    });
                }

                // 其他上下文变量
                CustomMolangParser.getAllBinding().forEach((key, value) -> {
                    if (value instanceof ContextBinding ctx) {
                        ctx.getAllName().forEach(s -> vars.add(String.format("%s.%s", key, s)));
                    }
                });

                // 自定义函数
                for (var name : cap.getModelRenderTarget().assets().userFunctions().keySet()) {
                    vars.add(String.format("fn.%s", name));
                }

                return new ArrayList<>(vars);
            }
        }
        return List.of();
    }

    public static List<String> allControllers() {
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
            var target = getTarget();
            if (target != null) {
                //noinspection UnnecessaryLocalVariable
                List<String> vars = target.getAnimationData()
                        .getAnimationControllers()
                        .stream()
                        .map(IAnimationController::getName)
                        .collect(Collectors.toList());
                return vars;
            }
        }
        return List.of();
    }

    @Nullable
    private static CustomEntity<?> getTarget() {
        var target = RenderDebugAnimationScreenEvent.getTarget();
        if (target == null) {
            EntityPlayerSP player = Minecraft.getMinecraft().player;
            if (player != null) {
                target = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
            }
        }
        return target;
    }

    public static Entity getClientEntity(@Nullable World world, ICommandSender sender, String target) throws CommandException {
        if (world == null) {
            throw new EntityNotFoundException(target);
        }

        if (EntitySelector.isSelector(target)) {
            Entity entity = EntitySelector.matchOneEntity(sender, target, Entity.class);
            if (entity == null) {
                throw new EntityNotFoundException(target);
            }
            return entity;
        }

        Entity player = world.getPlayerEntityByName(target);
        if (player != null) {
            return player;
        }

        try {
            UUID uuid = UUID.fromString(target);
            for (Entity entity : world.getLoadedEntityList()) {
                if (uuid.equals(entity.getUniqueID())) {
                    return entity;
                }
            }
        } catch (IllegalArgumentException ignored) {
            if (target.split("-").length == 5) {
                throw new EntityNotFoundException("commands.generic.entity.invalidUuid", target);
            }
        }

        throw new EntityNotFoundException(target);
    }

}
