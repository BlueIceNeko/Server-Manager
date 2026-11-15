package top.blueicemiaow.servermanager.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import top.blueicemiaow.servermanager.tools.ArgParser;
import top.blueicemiaow.servermanager.tools.FsUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class Rm {
    private static final String USAGE = "usage: rm [-rRfdv] [--] file ...";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("rm")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> execute(
                                context.getSource(),
                                StringArgumentType.getString(context, "args"))))
                .executes(context -> {
                    FsUtils.error(context.getSource(), "rm: missing operand");
                    return 0;
                }));
    }

    private static int execute(CommandSourceStack source, String rawArguments) {
        ArgParser arguments;
        try {
            arguments = new ArgParser(FsUtils.tokenize(rawArguments), "", Set.of("help"));
        } catch (IllegalArgumentException exception) {
            FsUtils.error(source, "rm: " + exception.getMessage());
            return 0;
        }

        if (arguments.hasLong("help")) {
            FsUtils.success(source, USAGE);
            return 1;
        }

        String invalid = arguments.flagsOnly("rRfdv");
        if (!invalid.isEmpty() || !arguments.unknown().isEmpty()) {
            FsUtils.error(source, "rm: invalid option -- " + (invalid.isEmpty() ? arguments.unknownOptions() : invalid));
            FsUtils.success(source, USAGE);
            return 0;
        }

        boolean recursive = arguments.has('r') || arguments.has('R');
        boolean force = arguments.has('f');
        boolean directories = arguments.has('d');
        boolean verbose = arguments.has('v');

        if (arguments.operands().isEmpty()) {
            if (!force) {
                FsUtils.error(source, "rm: missing operand");
                return 0;
            }
            return 1;
        }

        boolean failed = false;

        for (String operand : arguments.operands()) {
            Path path = FsUtils.resolve(source, operand);

            if (FsUtils.isDotOrDotDot(operand)) {
                FsUtils.error(source, "rm: refusing to remove '.' or '..' directory: skipping "
                        + FsUtils.quote(operand));
                failed = true;
                continue;
            }

            if (!FsUtils.exists(path)) {
                if (!force) {
                    FsUtils.error(source, "rm: cannot remove " + FsUtils.quote(operand) + ": No such file or directory");
                    failed = true;
                }
                continue;
            }

            if (FsUtils.isRoot(path)) {
                FsUtils.error(source, "rm: it is dangerous to operate recursively on " + FsUtils.quote(path.toString()));
                failed = true;
                continue;
            }

            try {
                if (FsUtils.isDirectory(path)) {
                    if (recursive) {
                        FsUtils.deleteRecursively(path);
                        if (verbose) {
                            FsUtils.success(source, "removed " + FsUtils.quote(operand));
                        }
                    } else if (directories) {
                        Files.delete(path);
                        if (verbose) {
                            FsUtils.success(source, "removed directory " + FsUtils.quote(operand));
                        }
                    } else {
                        FsUtils.error(source, "rm: cannot remove " + FsUtils.quote(operand) + ": Is a directory");
                        failed = true;
                    }
                } else {
                    Files.delete(path);
                    if (verbose) {
                        FsUtils.success(source, "removed " + FsUtils.quote(operand));
                    }
                }
            } catch (IOException exception) {
                FsUtils.error(source, "rm: cannot remove " + FsUtils.quote(operand) + ": " + FsUtils.reason(exception));
                failed = true;
            }
        }

        return failed ? 0 : 1;
    }
}
