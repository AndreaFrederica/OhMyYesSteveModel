package com.elfmcys.ysm.util;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.relauncher.Side;
import org.jetbrains.annotations.Nullable;

public class CommandUtil {
    private static final String MESSAGE_HEADER = "§6§l【§aYSM§6§l】§r";

    public static ITextComponent wrapMessage(ITextComponent message) {
        return new TextComponentString(MESSAGE_HEADER).appendSibling(message);
    }

    // 是否为单人模式，或联机模式下的房主
    public static boolean isLocalPlayer(Entity player) {
        return player != null && FMLCommonHandler.instance().getSide() == Side.CLIENT && player.getUniqueID().equals(Minecraft.getMinecraft().getSession().getProfile().getId());
    }

    public static void sendFailure(ICommandSender sender, ITextComponent component) {
        component.getStyle().setColor(TextFormatting.RED);
        sender.sendMessage(component);
    }

    public static void sendAsyncFeedback(final MinecraftServer server, @Nullable final ICommandSender oldSender,
                                         final ICommand command, final boolean allowLogging,
                                         final String translationKey, final Object... translationArgs) {
        server.addScheduledTask(() -> {
            ICommandSender sender = null;
            if (oldSender != null && oldSender.getCommandSenderEntity() instanceof EntityPlayerMP player) {
                EntityPlayerMP newSender = server.getPlayerList().getPlayerByUUID(player.getUniqueID());
                //noinspection ConstantValue
                if (newSender != null) {
                    sender = newSender;
                }
            }
            if (sender == null) {
                sender = server;
            }
            if (allowLogging) {
                CommandBase.notifyCommandListener(sender, command, translationKey, translationArgs);
            } else {
                sender.sendMessage(new TextComponentTranslation(translationKey, translationArgs));
            }
        });
    }
}
