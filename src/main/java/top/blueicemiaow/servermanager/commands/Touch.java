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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

public class Touch {
    private static final String USAGE = "usage: touch [-amch] [-t [[CC]YY]MMDDhhmm[.ss]] [-d date] [-r file] [--] file ...";

    private static final DateTimeFormatter[] DATE_TIME_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm", Locale.ROOT)
    };

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT),
            DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ROOT)
    };

    private static final DateTimeFormatter[] TIME_FORMATS = {
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("touch")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> execute(
                                context.getSource(),
                                StringArgumentType.getString(context, "args"))))
                .executes(context -> {
                    FsUtils.error(context.getSource(), "touch: missing file operand");
                    return 0;
                }));
    }

    private static int execute(CommandSourceStack source, String rawArguments) {
        ArgParser arguments;
        try {
            arguments = new ArgParser(FsUtils.tokenize(rawArguments), "tdr",
                    Set.of("help", "no-create", "no-dereference"));
        } catch (IllegalArgumentException exception) {
            FsUtils.error(source, "touch: " + exception.getMessage());
            return 0;
        }

        if (arguments.hasLong("help")) {
            FsUtils.success(source, USAGE);
            return 1;
        }

        String invalidFlags = arguments.flagsOnly("amctdrh");
        String invalidValues = arguments.valueOptionsOnly("tdr");
        if (!invalidFlags.isEmpty() || !invalidValues.isEmpty() || !arguments.unknown().isEmpty()) {
            String invalid = !invalidFlags.isEmpty() ? invalidFlags : !invalidValues.isEmpty() ? invalidValues : arguments.unknownOptions();
            FsUtils.error(source, "touch: invalid option -- " + invalid);
            FsUtils.success(source, USAGE);
            return 0;
        }

        if (arguments.operands().isEmpty()) {
            FsUtils.error(source, "touch: missing file operand");
            return 0;
        }

        boolean noCreate = arguments.has('c') || arguments.hasLong("no-create");
        boolean noDereference = arguments.has('h') || arguments.hasLong("no-dereference");
        boolean setAccess = arguments.has('a') || !arguments.has('m');
        boolean setModified = arguments.has('m') || !arguments.has('a');

        LinkOption[] linkOptions = noDereference
                ? new LinkOption[]{LinkOption.NOFOLLOW_LINKS}
                : new LinkOption[0];

        FileTime accessTime;
        FileTime modifiedTime;

        String referenceArgument = arguments.value('r');
        if (referenceArgument != null) {
            Path reference = FsUtils.resolve(source, referenceArgument);
            if (!Files.exists(reference, linkOptions)) {
                FsUtils.error(source, "touch: failed to get attributes of " + FsUtils.quote(referenceArgument)
                        + ": No such file or directory");
                return 0;
            }

            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        reference, BasicFileAttributes.class, linkOptions);
                accessTime = attributes.lastAccessTime();
                modifiedTime = attributes.lastModifiedTime();
            } catch (IOException exception) {
                FsUtils.error(source, "touch: failed to get attributes of " + FsUtils.quote(referenceArgument)
                        + ": " + FsUtils.reason(exception));
                return 0;
            }
        } else {
            LocalDateTime dateTime = LocalDateTime.now();
            String timestampArgument = arguments.value('t');
            String dateArgument = arguments.value('d');

            try {
                if (timestampArgument != null) {
                    dateTime = parseTouchTimestamp(timestampArgument);
                } else if (dateArgument != null) {
                    dateTime = parseDate(dateArgument);
                }
            } catch (RuntimeException exception) {
                String invalid = timestampArgument != null ? timestampArgument : dateArgument;
                FsUtils.error(source, "touch: invalid date format " + FsUtils.quote(invalid));
                return 0;
            }

            Instant instant = dateTime.atZone(ZoneId.systemDefault()).toInstant();
            accessTime = FileTime.from(instant);
            modifiedTime = FileTime.from(instant);
        }

        boolean failed = false;

        for (String operand : arguments.operands()) {
            Path path = FsUtils.resolve(source, operand);

            try {
                if (!Files.exists(path, linkOptions)) {
                    if (noCreate) {
                        continue;
                    }

                    Path creationTarget = path;
                    if (!noDereference && Files.isSymbolicLink(path)) {
                        // Match touch: a dangling symlink causes its target to be created.
                        Path linkTarget = Files.readSymbolicLink(path);
                        creationTarget = path.getParent() == null
                                ? linkTarget
                                : path.getParent().resolve(linkTarget);
                    }

                    Path parent = creationTarget.getParent();
                    if (parent != null && !Files.isDirectory(parent)) {
                        FsUtils.error(source, "touch: cannot touch " + FsUtils.quote(operand)
                                + ": No such file or directory");
                        failed = true;
                        continue;
                    }
                    Files.createFile(creationTarget);
                }

                BasicFileAttributeView view = Files.getFileAttributeView(
                        path, BasicFileAttributeView.class, linkOptions);
                if (view == null) {
                    FsUtils.error(source, "touch: cannot touch " + FsUtils.quote(operand)
                            + ": Operation not supported");
                    failed = true;
                    continue;
                }

                BasicFileAttributes attributes = view.readAttributes();
                FileTime newAccess = setAccess ? accessTime : attributes.lastAccessTime();
                FileTime newModified = setModified ? modifiedTime : attributes.lastModifiedTime();
                view.setTimes(newModified, newAccess, null);
            } catch (IOException exception) {
                FsUtils.error(source, "touch: cannot touch " + FsUtils.quote(operand) + ": "
                        + FsUtils.reason(exception));
                failed = true;
            }
        }

        return failed ? 0 : 1;
    }

    /**
     * Parses the POSIX touch -t format: [[CC]YY]MMDDhhmm[.ss].
     */
    private static LocalDateTime parseTouchTimestamp(String value) {
        String text = value.trim();
        int second = 0;

        int dot = text.indexOf('.');
        if (dot >= 0) {
            String seconds = text.substring(dot + 1);
            if (seconds.length() != 2) {
                throw new IllegalArgumentException("invalid seconds");
            }
            second = Integer.parseInt(seconds);
            text = text.substring(0, dot);
        }

        int year;
        int month;
        int day;
        int hour;
        int minute;

        if (text.length() == 8) {
            year = LocalDate.now().getYear();
            month = Integer.parseInt(text.substring(0, 2));
            day = Integer.parseInt(text.substring(2, 4));
            hour = Integer.parseInt(text.substring(4, 6));
            minute = Integer.parseInt(text.substring(6, 8));
        } else if (text.length() == 10) {
            int shortYear = Integer.parseInt(text.substring(0, 2));
            year = shortYear >= 69 ? 1900 + shortYear : 2000 + shortYear;
            month = Integer.parseInt(text.substring(2, 4));
            day = Integer.parseInt(text.substring(4, 6));
            hour = Integer.parseInt(text.substring(6, 8));
            minute = Integer.parseInt(text.substring(8, 10));
        } else if (text.length() == 12) {
            year = Integer.parseInt(text.substring(0, 4));
            month = Integer.parseInt(text.substring(4, 6));
            day = Integer.parseInt(text.substring(6, 8));
            hour = Integer.parseInt(text.substring(8, 10));
            minute = Integer.parseInt(text.substring(10, 12));
        } else {
            throw new IllegalArgumentException("invalid timestamp length");
        }

        return LocalDateTime.of(year, month, day, hour, minute, second);
    }

    private static LocalDateTime parseDate(String value) {
        String text = value.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        switch (lower) {
            case "now":
                return LocalDateTime.now();
            case "today":
                return LocalDate.now().atStartOfDay();
            case "yesterday":
                return LocalDate.now().minusDays(1).atStartOfDay();
            case "tomorrow":
                return LocalDate.now().plusDays(1).atStartOfDay();
            default:
                break;
        }

        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            // Try the remaining formats below.
        }

        for (DateTimeFormatter format : DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, format);
            } catch (DateTimeParseException ignored) {
                // Keep trying.
            }
        }

        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(text, format).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // Keep trying.
            }
        }

        for (DateTimeFormatter format : TIME_FORMATS) {
            try {
                return LocalTime.parse(text, format).atDate(LocalDate.now());
            } catch (DateTimeParseException ignored) {
                // Keep trying.
            }
        }

        throw new IllegalArgumentException("unrecognized date");
    }
}
