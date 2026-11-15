package top.blueicemiaow.servermanager.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import top.blueicemiaow.servermanager.tools.WorkspaceHelper;

public class PWD {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("pwd").requires(Commands.hasPermission(Commands.LEVEL_ADMINS)).executes(arguments -> {
            if (arguments.getSource().getEntity() instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.literal(WorkspaceHelper.getWorkspace(player).toString()));
            }
            return 0;
        }));
    }
}
