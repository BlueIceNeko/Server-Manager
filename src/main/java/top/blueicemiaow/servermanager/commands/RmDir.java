package top.blueicemiaow.servermanager.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import top.blueicemiaow.servermanager.tools.ArgParser;
import top.blueicemiaow.servermanager.tools.FsUtils;
import top.blueicemiaow.servermanager.tools.WorkspaceHelper;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class RmDir {
    private static final String USAGE = "usage: rmdir [-pv] [--ignore-fail-on-non-empty] [--] directory ...";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("rmdir")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> execute(
                                context.getSource(),
                                StringArgumentType.getString(context, "args"))))
                .executes(context -> {
                    FsUtils.error(context.getSource(), "rmdir: missing operand");
                    return 0;
                }));
    }

    private static int execute(CommandSourceStack source, String rawArguments) {
        ArgParser arguments;
        try {
            arguments = new ArgParser(FsUtils.tokenize(rawArguments), "",
                    Set.of("help", "ignore-fail-on-non-empty"));
        } catch (IllegalArgumentException exception) {
            FsUtils.error(source, "rmdir: " + exception.getMessage());
            return 0;
        }

        if (arguments.hasLong("help")) {
            FsUtils.success(source, USAGE);
            return 1;
        }

        String invalid = arguments.flagsOnly("pv");
        if (!invalid.isEmpty() || !arguments.unknown().isEmpty()) {
            FsUtils.error(source, "rmdir: invalid option -- " + (invalid.isEmpty() ? arguments.unknownOptions() : invalid));
            FsUtils.success(source, USAGE);
            return 0;
        }

        if (arguments.operands().isEmpty()) {
            FsUtils.error(source, "rmdir: missing operand");
            return 0;
        }

        boolean removeParents = arguments.has('p');
        boolean verbose = arguments.has('v');
        boolean ignoreNonEmpty = arguments.hasLong("ignore-fail-on-non-empty");
        boolean failed = false;

        for (String operand : arguments.operands()) {
            Path path = FsUtils.resolve(source, operand);

            if (FsUtils.isDotOrDotDot(operand)) {
                FsUtils.error(source, "rmdir: failed to remove " + FsUtils.quote(operand) + ": Invalid argument");
                failed = true;
                continue;
            }

            if (!FsUtils.exists(path)) {
                FsUtils.error(source, "rmdir: failed to remove " + FsUtils.quote(operand) + ": No such file or directory");
                failed = true;
                continue;
            }
            if (!FsUtils.isDirectory(path)) {
                FsUtils.error(source, "rmdir: failed to remove " + FsUtils.quote(operand) + ": Not a directory");
                failed = true;
                continue;
            }

            try {
                Files.delete(path);
                if (verbose) {
                    FsUtils.success(source, "rmdir: removing directory, " + FsUtils.quote(operand));
                }
            } catch (DirectoryNotEmptyException exception) {
                if (!ignoreNonEmpty) {
                    FsUtils.error(source, "rmdir: failed to remove " + FsUtils.quote(operand) + ": Directory not empty");
                    failed = true;
                }
                continue;
            } catch (IOException exception) {
                FsUtils.error(source, "rmdir: failed to remove " + FsUtils.quote(operand) + ": " + FsUtils.reason(exception));
                failed = true;
                continue;
            }

            if (removeParents) {
                if (!removeParents(source, path.getParent(), verbose, ignoreNonEmpty)) {
                    failed = true;
                }
            }
        }

        return failed ? 0 : 1;
    }

    private static boolean removeParents(CommandSourceStack source, Path parent, boolean verbose, boolean ignoreNonEmpty) {
        Path workspace = WorkspaceHelper.getWorkspace(source).toAbsolutePath().normalize();

        while (parent != null && !parent.equals(workspace) && !FsUtils.isRoot(parent)) {
            if (!FsUtils.isDirectory(parent)) {
                // Never walk through or delete a symlink while removing parents.
                return true;
            }

            try {
                Files.delete(parent);
                if (verbose) {
                    FsUtils.success(source, "rmdir: removing directory, "
                            + FsUtils.quote(FsUtils.displayPath(source, parent)));
                }
            } catch (DirectoryNotEmptyException exception) {
                if (!ignoreNonEmpty) {
                    FsUtils.error(source, "rmdir: failed to remove "
                            + FsUtils.quote(FsUtils.displayPath(source, parent)) + ": Directory not empty");
                    return false;
                }
                return true;
            } catch (IOException exception) {
                FsUtils.error(source, "rmdir: failed to remove "
                        + FsUtils.quote(FsUtils.displayPath(source, parent)) + ": " + FsUtils.reason(exception));
                return false;
            }

            parent = parent.getParent();
        }
        return true;
    }
}
