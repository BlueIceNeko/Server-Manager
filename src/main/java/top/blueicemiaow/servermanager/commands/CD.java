package top.blueicemiaow.servermanager.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import top.blueicemiaow.servermanager.tools.FsUtils;
import top.blueicemiaow.servermanager.tools.WorkspaceHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CD {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("cd")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .executes(context -> changeDirectory(context.getSource(), List.of()))
                .then(Commands.argument("path", StringArgumentType.greedyString())
                        .executes(context -> {
                            try {
                                return changeDirectory(context.getSource(),
                                        FsUtils.tokenize(StringArgumentType.getString(context, "path")));
                            } catch (IllegalArgumentException exception) {
                                FsUtils.error(context.getSource(), "cd: " + exception.getMessage());
                                return 0;
                            }
                        })));
    }

    private static int changeDirectory(CommandSourceStack source, List<String> arguments) {
        if (arguments.size() > 1) {
            FsUtils.error(source, "cd: too many arguments");
            return 0;
        }

        String target = arguments.isEmpty() ? "~" : arguments.get(0);

        if (target.equals("-")) {
            Path previous = WorkspaceHelper.getPreviousWorkspace(source);
            if (previous == null) {
                FsUtils.error(source, "cd: OLDPWD not set");
                return 0;
            }
            if (!Files.isDirectory(previous)) {
                FsUtils.error(source, "cd: no such file or directory: " + previous);
                return 0;
            }
            WorkspaceHelper.setWorkspace(source, previous);
            FsUtils.success(source, WorkspaceHelper.getWorkspace(source).toString());
            return 1;
        }

        Path path = FsUtils.resolve(source, target);
        if (!Files.exists(path)) {
            FsUtils.error(source, "cd: no such file or directory: " + target);
            return 0;
        }
        if (!Files.isDirectory(path)) {
            FsUtils.error(source, "cd: not a directory: " + target);
            return 0;
        }

        WorkspaceHelper.setWorkspace(source, path);
        return 1;
    }
}
