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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

public class Mv {
    private static final String USAGE = "usage: mv [-fnv] [--] source ... destination";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("mv")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> execute(
                                context.getSource(),
                                StringArgumentType.getString(context, "args"))))
                .executes(context -> {
                    FsUtils.error(context.getSource(), "mv: missing file operand");
                    return 0;
                }));
    }

    private static int execute(CommandSourceStack source, String rawArguments) {
        ArgParser arguments;
        try {
            arguments = new ArgParser(FsUtils.tokenize(rawArguments), "", Set.of("help"));
        } catch (IllegalArgumentException exception) {
            FsUtils.error(source, "mv: " + exception.getMessage());
            return 0;
        }

        if (arguments.hasLong("help")) {
            FsUtils.success(source, USAGE);
            return 1;
        }

        String invalid = arguments.flagsOnly("fnv");
        if (!invalid.isEmpty() || !arguments.unknown().isEmpty()) {
            FsUtils.error(source, "mv: invalid option -- " + (invalid.isEmpty() ? arguments.unknownOptions() : invalid));
            FsUtils.success(source, USAGE);
            return 0;
        }

        List<String> operands = arguments.operands();
        if (operands.isEmpty()) {
            FsUtils.error(source, "mv: missing file operand");
            return 0;
        }
        if (operands.size() == 1) {
            FsUtils.error(source, "mv: missing destination file operand after " + FsUtils.quote(operands.get(0)));
            return 0;
        }

        boolean noClobber = arguments.has('n');
        boolean verbose = arguments.has('v');

        List<String> sources = operands.subList(0, operands.size() - 1);
        String destinationOperand = operands.get(operands.size() - 1);
        Path destination = FsUtils.resolve(source, destinationOperand);
        boolean destinationIsDirectory = Files.isDirectory(destination);

        if (sources.size() > 1 && !destinationIsDirectory) {
            FsUtils.error(source, "mv: target " + FsUtils.quote(destinationOperand) + " is not a directory");
            return 0;
        }

        boolean failed = false;

        for (String operand : sources) {
            Path path = FsUtils.resolve(source, operand);

            if (!FsUtils.exists(path)) {
                FsUtils.error(source, "mv: cannot stat " + FsUtils.quote(operand) + ": No such file or directory");
                failed = true;
                continue;
            }

            if (path.getFileName() == null) {
                FsUtils.error(source, "mv: cannot move " + FsUtils.quote(operand) + ": Invalid argument");
                failed = true;
                continue;
            }

            Path target = destinationIsDirectory ? destination.resolve(path.getFileName()) : destination;

            boolean sourceIsDirectory = FsUtils.isDirectory(path);
            if (sourceIsDirectory && target.startsWith(path)) {
                FsUtils.error(source, "mv: cannot move " + FsUtils.quote(operand)
                        + " to a subdirectory of itself, " + FsUtils.quote(target.toString()));
                failed = true;
                continue;
            }

            boolean targetExists = FsUtils.exists(target);
            boolean targetIsDirectory = FsUtils.isDirectory(target);

            if (path.equals(target) || sameFile(path, target)) {
                FsUtils.error(source, "mv: " + FsUtils.quote(operand) + " and " + FsUtils.quote(target.toString())
                        + " are the same file");
                failed = true;
                continue;
            }

            Path targetParent = target.getParent();
            if (targetParent != null && !Files.isDirectory(targetParent)) {
                FsUtils.error(source, "mv: cannot move " + FsUtils.quote(operand) + " to "
                        + FsUtils.quote(target.toString()) + ": No such file or directory");
                failed = true;
                continue;
            }

            if (targetExists) {
                if (noClobber) {
                    if (verbose) {
                        FsUtils.success(source, "skipped " + FsUtils.quote(operand) + " -> " + FsUtils.quote(target.toString()));
                    }
                    continue;
                }
                if (targetIsDirectory && !sourceIsDirectory) {
                    FsUtils.error(source, "mv: cannot overwrite directory " + FsUtils.quote(target.toString())
                            + " with non-directory");
                    failed = true;
                    continue;
                }
                if (!targetIsDirectory && sourceIsDirectory) {
                    FsUtils.error(source, "mv: cannot overwrite non-directory " + FsUtils.quote(target.toString())
                            + " with directory " + FsUtils.quote(operand));
                    failed = true;
                    continue;
                }
                if (targetIsDirectory && sourceIsDirectory) {
                    try {
                        if (!FsUtils.isEmptyDirectory(target)) {
                            FsUtils.error(source, "mv: cannot move " + FsUtils.quote(operand) + " to "
                                    + FsUtils.quote(target.toString()) + ": Directory not empty");
                            failed = true;
                            continue;
                        }
                    } catch (IOException exception) {
                        FsUtils.error(source, "mv: cannot access " + FsUtils.quote(target.toString()) + ": "
                                + FsUtils.reason(exception));
                        failed = true;
                        continue;
                    }
                }
            }

            try {
                move(path, target, targetIsDirectory && sourceIsDirectory);
            } catch (IOException exception) {
                FsUtils.error(source, "mv: cannot move " + FsUtils.quote(operand) + " to "
                        + FsUtils.quote(target.toString()) + ": " + FsUtils.reason(exception));
                failed = true;
                continue;
            }

            WorkspaceHelper.remapWorkspaces(path, target);
            if (verbose) {
                FsUtils.success(source, "renamed " + FsUtils.quote(operand) + " -> " + FsUtils.quote(target.toString()));
            }
        }

        return failed ? 0 : 1;
    }

    private static void move(Path source, Path target, boolean replaceEmptyDirectory) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        } catch (IOException firstFailure) {
            // Files.move is not guaranteed to fall back to copy+delete on every platform/filesystem.
            try {
                if (replaceEmptyDirectory && FsUtils.exists(target)) {
                    Files.delete(target);
                }
                FsUtils.copyRecursively(source, target);
                FsUtils.deleteRecursively(source);
            } catch (IOException secondFailure) {
                firstFailure.addSuppressed(secondFailure);
                throw firstFailure;
            }
        }
    }

    private static boolean sameFile(Path first, Path second) {
        try {
            return Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second);
        } catch (IOException ignored) {
            return false;
        }
    }
}
