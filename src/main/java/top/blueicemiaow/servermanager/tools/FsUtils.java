package top.blueicemiaow.servermanager.tools;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared helpers for the shell-like filesystem commands.
 */
public final class FsUtils {
    public static final DateTimeFormatter LS_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    private FsUtils() {
    }

    public static Path resolve(CommandSourceStack source, String input) {
        return resolve(WorkspaceHelper.getWorkspace(source), input);
    }

    public static Path resolve(Path base, String input) {
        String value = input == null ? "" : input.trim();

        if (value.isEmpty() || value.equals(".")) {
            return base.toAbsolutePath().normalize();
        }
        if (value.equals("~")) {
            return WorkspaceHelper.defaultWorkspace.toAbsolutePath().normalize();
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            value = WorkspaceHelper.defaultWorkspace.resolve(value.substring(2)).toString();
        }

        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = base.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    /**
     * Splits a command argument string like a shell would. Quotes group whitespace;
     * backslashes are kept as-is so Windows paths can be typed without escaping.
     */
    public static List<String> tokenize(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean inToken = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken || current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                    inToken = false;
                }
            } else {
                current.append(c);
                inToken = true;
            }
        }

        if (quote != 0) {
            throw new IllegalArgumentException("unmatched quote");
        }
        if (inToken || current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    public static void success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    public static void error(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
    }

    public static String quote(String value) {
        return "'" + value + "'";
    }

    public static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean isDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean isHidden(Path path) {
        Path name = path.getFileName();
        if (name != null && name.toString().startsWith(".")) {
            return true;
        }
        try {
            return Files.isHidden(path);
        } catch (IOException ignored) {
            return false;
        }
    }

    public static String permissions(Path path) {
        boolean link = Files.isSymbolicLink(path);
        boolean directory = isDirectory(path);
        String type = link ? "l" : directory ? "d" : "-";

        try {
            PosixFileAttributeView view = Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view != null) {
                return type + PosixFilePermissions.toString(view.readAttributes().permissions());
            }
        } catch (IOException ignored) {
            // Fall through to a portable approximation.
        }

        return type + (directory ? "rwxr-xr-x" : "rw-r--r--");
    }

    public static long size(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    public static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ignored) {
            return FileTime.from(Instant.EPOCH);
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return Long.toString(bytes);
        }

        String[] units = {"K", "M", "G", "T", "P", "E"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);

        String number = value < 10.0
                ? String.format(Locale.ROOT, "%.1f", value)
                : String.format(Locale.ROOT, "%.0f", value);
        return number + units[unit];
    }

    public static String classify(Path path) {
        if (Files.isSymbolicLink(path)) {
            return "@";
        }
        if (isDirectory(path)) {
            return "/";
        }
        if (Files.isRegularFile(path) && Files.isExecutable(path)) {
            return "*";
        }
        return "";
    }

    public static String reason(IOException exception) {
        if (exception instanceof java.nio.file.NoSuchFileException) {
            return "No such file or directory";
        }
        if (exception instanceof java.nio.file.AccessDeniedException) {
            return "Permission denied";
        }
        if (exception instanceof java.nio.file.DirectoryNotEmptyException) {
            return "Directory not empty";
        }
        if (exception instanceof java.nio.file.FileAlreadyExistsException) {
            return "File exists";
        }
        if (exception instanceof FileSystemException fileSystemException) {
            String reason = fileSystemException.getReason();
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (isDirectory(path)) {
            List<Path> children;
            try (var stream = Files.list(path)) {
                children = stream.toList();
            }
            for (Path child : children) {
                deleteRecursively(child);
            }
        }
        Files.delete(path);
    }

    public static void copyRecursively(Path source, Path target) throws IOException {
        if (isDirectory(source)) {
            Files.createDirectories(target);
            List<Path> children;
            try (var stream = Files.list(source)) {
                children = stream.toList();
            }
            for (Path child : children) {
                copyRecursively(child, target.resolve(child.getFileName().toString()));
            }
        } else {
            Files.copy(source, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES,
                    LinkOption.NOFOLLOW_LINKS);
        }
    }

    public static boolean isRoot(Path path) {
        Path root = path.getRoot();
        return root != null && path.equals(root);
    }

    public static boolean isEmptyDirectory(Path path) throws IOException {
        if (!isDirectory(path)) {
            return false;
        }
        try (var stream = Files.list(path)) {
            return stream.findAny().isEmpty();
        }
    }

    public static boolean isDotOrDotDot(String operand) {
        String value = operand.replace('\\', '/');
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        int slash = value.lastIndexOf('/');
        String last = slash >= 0 ? value.substring(slash + 1) : value;
        return last.equals(".") || last.equals("..");
    }

    /**
     * Prefers a workspace-relative path for user-facing listings, but keeps absolute
     * paths that point outside of the current workspace.
     */
    public static String displayPath(CommandSourceStack source, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path workspace = WorkspaceHelper.getWorkspace(source).toAbsolutePath().normalize();
        if (absolute.startsWith(workspace) && !absolute.equals(workspace)) {
            return workspace.relativize(absolute).toString();
        }
        return absolute.toString();
    }
}
