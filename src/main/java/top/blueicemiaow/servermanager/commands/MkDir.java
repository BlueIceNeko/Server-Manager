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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class MkDir {
    private static final String USAGE = "usage: mkdir [-pv] [--] directory ...";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("mkdir")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> execute(
                                context.getSource(),
                                StringArgumentType.getString(context, "args"))))
                .executes(context -> {
                    FsUtils.error(context.getSource(), "mkdir: missing operand");
                    return 0;
                }));
    }

    private static int execute(CommandSourceStack source, String rawArguments) {
        ArgParser arguments;
        try {
            arguments = new ArgParser(FsUtils.tokenize(rawArguments), "", Set.of("help"));
        } catch (IllegalArgumentException exception) {
            FsUtils.error(source, "mkdir: " + exception.getMessage());
            return 0;
        }

        if (arguments.hasLong("help")) {
            FsUtils.success(source, USAGE);
            return 1;
        }

        String invalid = arguments.flagsOnly("pv");
        if (!invalid.isEmpty() || !arguments.unknown().isEmpty()) {
            FsUtils.error(source, "mkdir: invalid option -- " + (invalid.isEmpty() ? arguments.unknownOptions() : invalid));
            FsUtils.success(source, USAGE);
            return 0;
        }

        if (arguments.operands().isEmpty()) {
            FsUtils.error(source, "mkdir: missing operand");
            return 0;
        }

        boolean parents = arguments.has('p');
        boolean verbose = arguments.has('v');
        boolean failed = false;

        for (String operand : arguments.operands()) {
            Path path = FsUtils.resolve(source, operand);

            try {
                if (parents) {
                    createParents(source, path, verbose);
                } else {
                    Files.createDirectory(path);
                    if (verbose) {
                        FsUtils.success(source, "mkdir: created directory " + FsUtils.quote(operand));
                    }
                }
            } catch (IOException exception) {
                FsUtils.error(source, "mkdir: cannot create directory " + FsUtils.quote(operand) + ": " + FsUtils.reason(exception));
                failed = true;
            }
        }

        return failed ? 0 : 1;
    }

    private static void createParents(CommandSourceStack source, Path path, boolean verbose) throws IOException {
        List<Path> missing = new ArrayList<>();
        Path current = path;

        while (current != null && !Files.exists(current)) {
            missing.add(current);
            current = current.getParent();
        }

        if (current != null && !Files.isDirectory(current)) {
            throw new java.nio.file.FileAlreadyExistsException(current.toString(), null, "Not a directory");
        }

        Collections.reverse(missing);
        if (missing.isEmpty()) {
            return;
        }

        Files.createDirectories(path);
        if (verbose) {
            for (Path directory : missing) {
                FsUtils.success(source, "mkdir: created directory "
                        + FsUtils.quote(FsUtils.displayPath(source, directory)));
            }
        }
    }
}
