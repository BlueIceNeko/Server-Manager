package top.blueicemiaow.servermanager.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import top.blueicemiaow.servermanager.tools.FsUtils;
import top.blueicemiaow.servermanager.tools.WorkspaceHelper;

public class PWD {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("pwd").requires(Commands.hasPermission(Commands.LEVEL_ADMINS)).executes(arguments -> {
            FsUtils.success(arguments.getSource(), WorkspaceHelper.getWorkspace(arguments.getSource()).toString());
            return 1;
        }));
    }
}
