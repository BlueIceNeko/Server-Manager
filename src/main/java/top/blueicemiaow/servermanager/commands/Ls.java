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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class Ls {
    private static final String USAGE = "usage: ls [-aAdlhrRtF1] [--] [path ...]";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("ls")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .executes(context -> execute(context.getSource(), ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> execute(
                                context.getSource(),
                                StringArgumentType.getString(context, "args")))));
    }

    private static int execute(CommandSourceStack source, String rawArguments) {
        ArgParser arguments;
        try {
            arguments = new ArgParser(FsUtils.tokenize(rawArguments), "", Set.of("help"));
        } catch (IllegalArgumentException exception) {
            FsUtils.error(source, "ls: " + exception.getMessage());
            return 0;
        }

        if (arguments.hasLong("help")) {
            FsUtils.success(source, USAGE);
            return 1;
        }

        String invalid = arguments.flagsOnly("aAdlhrRtF1");
        if (!invalid.isEmpty() || !arguments.unknown().isEmpty()) {
            FsUtils.error(source, "ls: invalid option -- " + (invalid.isEmpty() ? arguments.unknownOptions() : invalid));
            FsUtils.success(source, USAGE);
            return 0;
        }

        Options options = new Options(
                arguments.has('a') || arguments.has('A'),
                arguments.has('l'),
                arguments.has('h'),
                arguments.has('R'),
                arguments.has('d'),
                arguments.has('r'),
                arguments.has('t'),
                arguments.has('F'));

        List<String> targets = arguments.operands().isEmpty() ? List.of(".") : arguments.operands();
        boolean failed = false;

        for (String target : targets) {
            Path path = FsUtils.resolve(source, target);

            if (!FsUtils.exists(path)) {
                FsUtils.error(source, "ls: cannot access " + FsUtils.quote(target) + ": No such file or directory");
                failed = true;
                continue;
            }

            if (targets.size() > 1) {
                FsUtils.success(source, target + ":");
            }

            boolean listContents = !options.directoriesOnly
                    && Files.isDirectory(path)
                    && !(options.longFormat && Files.isSymbolicLink(path));

            try {
                if (listContents) {
                    listDirectory(source, path, false, options);
                } else {
                    printEntry(source, path, displayName(path), options, 0);
                }
            } catch (IOException exception) {
                FsUtils.error(source, "ls: cannot access " + FsUtils.quote(target) + ": " + FsUtils.reason(exception));
                failed = true;
            }

            if (targets.size() > 1) {
                FsUtils.success(source, "");
            }
        }

        return failed ? 0 : 1;
    }

    private static void listDirectory(CommandSourceStack source, Path directory, boolean printHeader, Options options) throws IOException {
        if (printHeader) {
            FsUtils.success(source, FsUtils.displayPath(source, directory) + ":");
        }

        List<Path> entries = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.forEach(entry -> {
                if (options.all || !FsUtils.isHidden(entry)) {
                    entries.add(entry);
                }
            });
        }

        Comparator<Path> comparator = Comparator.comparing(entry -> entry.getFileName().toString());
        if (options.byTime) {
            comparator = Comparator
                    .comparingLong((Path entry) -> FsUtils.lastModified(entry).toMillis())
                    .reversed()
                    .thenComparing(comparator);
        }
        if (options.reverse) {
            comparator = comparator.reversed();
        }
        entries.sort(comparator);

        long width = 0;
        if (options.longFormat) {
            for (Path entry : entries) {
                width = Math.max(width, sizeText(entry, options).length());
            }
        }

        for (Path entry : entries) {
            printEntry(source, entry, entry.getFileName() == null ? entry.toString() : entry.getFileName().toString(),
                    options, width);
        }

        if (options.recursive) {
            for (Path entry : entries) {
                if (FsUtils.isDirectory(entry) && !Files.isSymbolicLink(entry)) {
                    FsUtils.success(source, "");
                    listDirectory(source, entry, true, options);
                }
            }
        }
    }

    private static void printEntry(CommandSourceStack source, Path path, String name, Options options, long width) throws IOException {
        String suffix = options.classify ? FsUtils.classify(path) : "";
        String displayName = name + suffix;

        if (Files.isSymbolicLink(path)) {
            try {
                displayName += " -> " + Files.readSymbolicLink(path);
            } catch (IOException ignored) {
                // A link that cannot be read is still listed.
            }
        }

        if (!options.longFormat) {
            FsUtils.success(source, displayName);
            return;
        }

        String size = String.format(Locale.ROOT, "%" + Math.max(1, width) + "s", sizeText(path, options));
        String date = FsUtils.LS_DATE_FORMAT.format(Instant.ofEpochMilli(FsUtils.lastModified(path).toMillis()));
        FsUtils.success(source, FsUtils.permissions(path) + " " + size + " " + date + " " + displayName);
    }

    private static String sizeText(Path path, Options options) {
        long size = FsUtils.size(path);
        return options.humanReadable ? FsUtils.humanSize(size) : Long.toString(size);
    }

    private static String displayName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private record Options(boolean all, boolean longFormat, boolean humanReadable, boolean recursive,
                           boolean directoriesOnly, boolean reverse, boolean byTime, boolean classify) {
    }
}
