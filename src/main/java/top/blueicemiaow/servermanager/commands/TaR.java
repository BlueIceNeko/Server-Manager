package top.blueicemiaow.servermanager.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import top.blueicemiaow.servermanager.tools.ArgParser;
import top.blueicemiaow.servermanager.tools.FsUtils;
import top.blueicemiaow.servermanager.tools.WorkspaceHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/**
 * A deliberately small tar implementation backed by Apache Commons Compress.
 * It implements only -c, -x, -t, -r, -u, -d, -A, -f, -z, -j, -J, --lzma,
 * --help and -h. It is not GNU Tar and has far fewer features.
 */
public class TaR {
    private static final String NOTICE = "tar: feature-limited built-in archiver, NOT GNU Tar and not affiliated with GNU Tar.";
    private static final String USAGE = "usage: tar -c|-x|-t|-r|-u|-d|-A -f ARCHIVE [OPTIONS] [FILE ...]";
    private static final String HELP = """
            Operations (exactly one is required):
              -c             create a new archive
              -x             extract files from an archive
              -t             list the contents of an archive
              -r             append files to the end of an archive
              -u             append files newer than the archived copy
              -d             compare archive contents with the file system
              -A             append other archives to the end of an archive
              -h, --help     show this help and exit

            Archive options:
              -f ARCHIVE     use ARCHIVE as the archive file (required)
              -z             filter the archive through gzip
              -j             filter the archive through bzip2
              -J             filter the archive through xz
              --lzma         filter the archive through lzma

            Only these options are implemented. This is not GNU Tar; many GNU Tar options and behaviours are deliberately absent.""";

    private static final String OPERATION_FLAGS = "cxtrudA";
    private static final Set<String> LONG_FLAGS = Set.of("help", "lzma");

    private static final int MAX_OUTPUT_LINES = 300;
    private static final int MAX_OUTPUT_CHARS = 30000;
    private static final int MAX_LINE_LENGTH = 1000;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("tar")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .executes(context -> execute(context.getSource(), ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .executes(context -> execute(
                                context.getSource(),
                                StringArgumentType.getString(context, "args")))));
    }

    private static int execute(CommandSourceStack source, String rawArguments) {
        // Required first line for every invocation, including help and errors.
        FsUtils.success(source, NOTICE);

        List<String> tokens;
        try {
            tokens = FsUtils.tokenize(rawArguments == null ? "" : rawArguments);
        } catch (IllegalArgumentException exception) {
            FsUtils.error(source, "tar: " + exception.getMessage());
            return 0;
        }

        ArgParser arguments = new ArgParser(tokens, "f", LONG_FLAGS);

        if (arguments.hasLong("help") || arguments.has('h')) {
            FsUtils.success(source, USAGE);
            for (String line : HELP.split("\\R")) {
                FsUtils.success(source, line);
            }
            return 1;
        }

        String invalidFlags = arguments.flagsOnly("cxtrudAfzjJh");
        String invalidValues = arguments.valueOptionsOnly("f");
        if (!invalidFlags.isEmpty() || !invalidValues.isEmpty() || !arguments.unknown().isEmpty()) {
            String invalid = !invalidFlags.isEmpty()
                    ? invalidFlags
                    : !invalidValues.isEmpty() ? invalidValues : arguments.unknownOptions();
            FsUtils.error(source, "tar: invalid option -- " + invalid);
            FsUtils.success(source, USAGE);
            return 0;
        }

        StringBuilder operations = new StringBuilder();
        for (char option : OPERATION_FLAGS.toCharArray()) {
            if (arguments.has(option)) {
                operations.append(option);
            }
        }
        if (operations.isEmpty()) {
            FsUtils.error(source, "tar: you must specify one of the '-Acdtrux' options");
            FsUtils.success(source, USAGE);
            return 0;
        }
        if (operations.length() > 1) {
            FsUtils.error(source, "tar: you may not specify more than one '-Acdtrux' option");
            FsUtils.success(source, USAGE);
            return 0;
        }
        char operation = operations.charAt(0);

        Compression compression = Compression.NONE;
        int compressionOptions = 0;
        if (arguments.has('z')) {
            compression = Compression.GZIP;
            compressionOptions++;
        }
        if (arguments.has('j')) {
            compression = Compression.BZIP2;
            compressionOptions++;
        }
        if (arguments.has('J')) {
            compression = Compression.XZ;
            compressionOptions++;
        }
        if (arguments.hasLong("lzma")) {
            compression = Compression.LZMA;
            compressionOptions++;
        }
        if (compressionOptions > 1) {
            FsUtils.error(source, "tar: only one compression option may be used");
            return 0;
        }

        if ((operation == 'r' || operation == 'u' || operation == 'A') && compression != Compression.NONE) {
            FsUtils.error(source, "tar: cannot update compressed archives");
            return 0;
        }

        String archiveArgument = arguments.value('f');
        if (archiveArgument != null && archiveArgument.startsWith("=")) {
            archiveArgument = archiveArgument.substring(1);
        }
        if (archiveArgument == null || archiveArgument.isEmpty()) {
            FsUtils.error(source, "tar: no archive specified; use -f ARCHIVE");
            FsUtils.success(source, USAGE);
            return 0;
        }

        List<String> operands = new ArrayList<>(arguments.operands());
        if (operation == 'c' && operands.isEmpty()) {
            FsUtils.error(source, "tar: Cowardly refusing to create an empty archive");
            FsUtils.success(source, USAGE);
            return 0;
        }
        if ((operation == 'r' || operation == 'u') && operands.isEmpty()) {
            FsUtils.error(source, "tar: no files or directories specified");
            FsUtils.success(source, USAGE);
            return 0;
        }
        if (operation == 'A' && operands.isEmpty()) {
            FsUtils.error(source, "tar: no archives specified");
            FsUtils.success(source, USAGE);
            return 0;
        }

        Path workspace = WorkspaceHelper.getWorkspace(source);
        Path archive;
        try {
            archive = FsUtils.resolve(workspace, archiveArgument);
        } catch (RuntimeException exception) {
            FsUtils.error(source, "tar: invalid archive path " + FsUtils.quote(archiveArgument));
            return 0;
        }

        Compression selectedCompression = compression;
        Thread worker = new Thread(
                () -> run(source, workspace, archive, selectedCompression, operation, operands),
                "ServerManager-tar");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static void run(CommandSourceStack source, Path workspace, Path archive, Compression compression,
                            char operation, List<String> operands) {
        Reporter reporter = new Reporter(source);

        try {
            switch (operation) {
                case 'c' -> create(workspace, archive, compression, operands, reporter);
                case 'x' -> extract(workspace, archive, compression, operands, reporter);
                case 't' -> list(workspace, archive, compression, operands, reporter);
                case 'r' -> append(workspace, archive, operands, reporter, false);
                case 'u' -> append(workspace, archive, operands, reporter, true);
                case 'd' -> diff(workspace, archive, compression, operands, reporter);
                case 'A' -> concatenate(workspace, archive, operands, reporter);
                default -> reporter.fail("tar: unsupported operation " + operation);
            }
        } catch (IOException exception) {
            reporter.fail("tar: " + FsUtils.reason(exception));
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            reporter.fail("tar: " + (message == null || message.isBlank()
                    ? exception.getClass().getSimpleName() : message));
        } catch (LinkageError error) {
            reporter.fail("tar: compression support is unavailable: " + error.getMessage());
        }
    }

    private static void create(Path workspace, Path archive, Compression compression, List<String> operands,
                               Reporter reporter) throws IOException {
        Path parent = archive.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temp = Files.createTempFile(parent, ".tar-", ".part");
        int entries;
        try {
            Set<Path> excluded = Set.of(archive.toAbsolutePath().normalize(), temp.toAbsolutePath().normalize());
            entries = writeArchive(temp, compression, tar -> {
                int count = 0;
                for (String operand : operands) {
                    Path path = FsUtils.resolve(workspace, operand);
                    String base = archiveBase(workspace, path);
                    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        reporter.fail("tar: " + operand + ": Cannot stat: No such file or directory");
                        continue;
                    }
                    count += addPath(tar, path, base, reporter, null, excluded);
                }
                return count;
            });
            Files.move(temp, archive, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temp);
            throw exception;
        }

        reporter.info("tar: created " + FsUtils.quote(FsUtils.displayPath(reporter.source, archive))
                + " (" + entries + " entries)");
    }

    private static void append(Path workspace, Path archive, List<String> operands, Reporter reporter,
                               boolean update) throws IOException {
        if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            if (detectCompression(archive) != Compression.NONE) {
                throw new IOException("cannot update compressed archives");
            }
        }

        Path parent = archive.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Map<String, Long> existing = update ? readModTimes(archive) : Map.of();
        Path temp = Files.createTempFile(parent, ".tar-", ".part");
        int entries;
        try {
            Set<Path> excluded = Set.of(archive.toAbsolutePath().normalize(), temp.toAbsolutePath().normalize());
            entries = writeArchive(temp, Compression.NONE, tar -> {
                int count = 0;
                if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                    count += copyArchive(tar, archive, Compression.NONE);
                }
                for (String operand : operands) {
                    Path path = FsUtils.resolve(workspace, operand);
                    String base = archiveBase(workspace, path);
                    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        reporter.fail("tar: " + operand + ": Cannot stat: No such file or directory");
                        continue;
                    }
                    count += addPath(tar, path, base, reporter, existing, excluded);
                }
                return count;
            });
            Files.move(temp, archive, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temp);
            throw exception;
        }

        reporter.info("tar: " + (update ? "updated " : "appended to ")
                + FsUtils.quote(FsUtils.displayPath(reporter.source, archive))
                + " (archive now has " + entries + " entries)");
    }

    private static void concatenate(Path workspace, Path archive, List<String> operands,
                                    Reporter reporter) throws IOException {
        if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)
                && detectCompression(archive) != Compression.NONE) {
            throw new IOException("cannot update compressed archives");
        }

        Path parent = archive.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temp = Files.createTempFile(parent, ".tar-", ".part");
        int entries;
        try {
            Set<Path> excluded = Set.of(archive.toAbsolutePath().normalize(), temp.toAbsolutePath().normalize());
            entries = writeArchive(temp, Compression.NONE, tar -> {
                int count = 0;
                if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                    count += copyArchive(tar, archive, Compression.NONE);
                }
                for (String operand : operands) {
                    Path source = FsUtils.resolve(workspace, operand);
                    if (excluded.contains(source.toAbsolutePath().normalize())) {
                        continue;
                    }
                    if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                        reporter.fail("tar: " + operand + ": Cannot stat: No such file or directory");
                        continue;
                    }
                    Compression detected = detectCompression(source);
                    count += copyArchive(tar, source, detected);
                }
                return count;
            });
            Files.move(temp, archive, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temp);
            throw exception;
        }

        reporter.info("tar: appended " + operands.size() + " archive(s) to "
                + FsUtils.quote(FsUtils.displayPath(reporter.source, archive))
                + " (archive now has " + entries + " entries)");
    }

    private static int writeArchive(Path target, Compression compression, ArchiveWriter writer)
            throws IOException {
        try (OutputStream file = Files.newOutputStream(target,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             OutputStream compressed = wrapOutput(file, compression);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            tar.setAddPaxHeadersForNonAsciiNames(true);
            return writer.write(tar);
        }
    }

    private static int addPath(TarArchiveOutputStream tar, Path path, String entryName, Reporter reporter,
                               Map<String, Long> existingModTimes, Set<Path> excluded) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (excluded.contains(absolute)) {
            return 0;
        }

        String name = entryName.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }

        if (name.isEmpty()) {
            int count = 0;
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                for (Path child : listChildren(path)) {
                    count += addPath(tar, child, child.getFileName().toString(), reporter,
                            existingModTimes, excluded);
                }
            }
            return count;
        }

        if (Files.isSymbolicLink(path)) {
            TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
            entry.setLinkName(Files.readSymbolicLink(path).toString().replace('\\', '/'));
            entry.setModTime(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS));
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
            return 1;
        }

        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            TarArchiveEntry entry = new TarArchiveEntry(path, name, LinkOption.NOFOLLOW_LINKS);
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();

            int count = 1;
            for (Path child : listChildren(path)) {
                String childName = name + "/" + child.getFileName();
                count += addPath(tar, child, childName, reporter, existingModTimes, excluded);
            }
            return count;
        }

        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            if (existingModTimes != null) {
                Long archived = existingModTimes.get(name);
                long modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
                if (archived != null && archived >= modified) {
                    return 0;
                }
            }

            TarArchiveEntry entry = new TarArchiveEntry(path, name, LinkOption.NOFOLLOW_LINKS);
            tar.putArchiveEntry(entry);
            try (InputStream input = Files.newInputStream(path)) {
                input.transferTo(tar);
            }
            tar.closeArchiveEntry();
            return 1;
        }

        reporter.detail("tar: " + name + ": unsupported file type, skipped");
        return 0;
    }

    private static List<Path> listChildren(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.sorted().toList();
        }
    }

    private static String archiveBase(Path workspace, Path path) {
        Path base = workspace.toAbsolutePath().normalize();
        Path absolute = path.toAbsolutePath().normalize();

        if (absolute.equals(base)) {
            return "";
        }
        if (absolute.startsWith(base)) {
            return base.relativize(absolute).toString().replace('\\', '/');
        }

        Path name = absolute.getFileName();
        return name != null ? name.toString() : absolute.toString().replace('\\', '/');
    }

    private static void extract(Path workspace, Path archive, Compression compression, List<String> filters,
                                Reporter reporter) throws IOException {
        if (!Files.isRegularFile(archive)) {
            throw new java.nio.file.NoSuchFileException(archive.toString());
        }

        Path base = workspace.toAbsolutePath().normalize();
        Compression actual = compression == Compression.NONE ? detectCompression(archive) : compression;
        List<DeferredLink> links = new ArrayList<>();
        int entries = 0;
        int skipped = 0;

        try (InputStream file = Files.newInputStream(archive);
             InputStream input = wrapInput(file, actual);
             TarArchiveInputStream tar = new TarArchiveInputStream(input)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = normalizeEntryName(entry.getName());
                if (name == null) {
                    reporter.fail("tar: skipping unsafe path " + FsUtils.quote(entry.getName()));
                    skipped++;
                    continue;
                }
                if (!matches(name, filters)) {
                    continue;
                }

                Path target = base.resolve(name).normalize();
                if (!target.startsWith(base)) {
                    reporter.fail("tar: skipping path outside the workspace " + FsUtils.quote(entry.getName()));
                    skipped++;
                    continue;
                }

                if (entry.isDirectory()) {
                    if (name.isEmpty()) {
                        continue;
                    }
                    if (hasUnsafeAncestor(base, target)) {
                        reporter.fail("tar: skipping " + FsUtils.quote(entry.getName())
                                + ": parent is a symbolic link");
                        skipped++;
                        continue;
                    }
                    Files.createDirectories(target);
                    restoreTime(target, entry);
                    entries++;
                } else if (entry.isSymbolicLink()) {
                    if (name.isEmpty()) {
                        reporter.fail("tar: skipping invalid symbolic link entry");
                        skipped++;
                        continue;
                    }
                    String linkName = entry.getLinkName();
                    if (!isSafeLink(base, target, linkName)) {
                        reporter.fail("tar: skipping unsafe symbolic link " + FsUtils.quote(entry.getName())
                                + " -> " + FsUtils.quote(String.valueOf(linkName)));
                        skipped++;
                        continue;
                    }
                    links.add(new DeferredLink(target, linkName, false));
                    entries++;
                } else if (entry.isLink()) {
                    String linkName = normalizeEntryName(entry.getLinkName());
                    if (name.isEmpty() || linkName == null || linkName.isEmpty()) {
                        reporter.fail("tar: skipping invalid hard link entry " + FsUtils.quote(entry.getName()));
                        skipped++;
                        continue;
                    }
                    Path linkTarget = base.resolve(linkName).normalize();
                    if (!linkTarget.startsWith(base)) {
                        reporter.fail("tar: skipping unsafe hard link " + FsUtils.quote(entry.getName())
                                + " -> " + FsUtils.quote(entry.getLinkName()));
                        skipped++;
                        continue;
                    }
                    links.add(new DeferredLink(target, linkTarget.toString(), true));
                    entries++;
                } else if (entry.isFile()) {
                    if (name.isEmpty()) {
                        reporter.fail("tar: skipping invalid file entry");
                        skipped++;
                        continue;
                    }
                    if (writeFile(base, target, tar, entry, reporter)) {
                        entries++;
                    } else {
                        skipped++;
                    }
                } else {
                    reporter.detail("tar: skipping unsupported entry " + FsUtils.quote(entry.getName()));
                    skipped++;
                }
            }
        }

        for (DeferredLink link : links) {
            try {
                createLink(base, link);
            } catch (IOException exception) {
                reporter.fail("tar: " + FsUtils.quote(FsUtils.displayPath(reporter.source, link.path()))
                        + ": " + FsUtils.reason(exception));
                skipped++;
            }
        }

        reporter.info("tar: extracted " + entries + " entries from "
                + FsUtils.quote(FsUtils.displayPath(reporter.source, archive))
                + (skipped > 0 ? " (" + skipped + " skipped)" : ""));
    }

    private static boolean writeFile(Path base, Path target, TarArchiveInputStream tar, TarArchiveEntry entry,
                                     Reporter reporter) throws IOException {
        if (hasUnsafeAncestor(base, target)) {
            reporter.fail("tar: skipping " + FsUtils.quote(entry.getName()) + ": parent is a symbolic link");
            tar.transferTo(OutputStream.nullOutputStream());
            return false;
        }

        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            reporter.fail("tar: cannot overwrite directory " + FsUtils.quote(entry.getName()));
            tar.transferTo(OutputStream.nullOutputStream());
            return false;
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.isSymbolicLink(target)) {
            Files.delete(target);
        }

        try (OutputStream output = Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            tar.transferTo(output);
        }
        restoreTime(target, entry);
        return true;
    }

    private static void createLink(Path base, DeferredLink link) throws IOException {
        Path target = link.path();
        if (hasUnsafeAncestor(base, target)) {
            throw new IOException("parent is a symbolic link");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("cannot overwrite a directory");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(target);
        }

        if (link.hardLink()) {
            Path existing = Path.of(link.linkName());
            Files.createLink(target, existing);
        } else {
            Files.createSymbolicLink(target, Path.of(link.linkName()));
        }
    }

    private static void list(Path workspace, Path archive, Compression compression, List<String> filters,
                             Reporter reporter) throws IOException {
        if (!Files.isRegularFile(archive)) {
            throw new java.nio.file.NoSuchFileException(archive.toString());
        }

        Compression actual = compression == Compression.NONE ? detectCompression(archive) : compression;
        int entries = 0;

        try (InputStream file = Files.newInputStream(archive);
             InputStream input = wrapInput(file, actual);
             TarArchiveInputStream tar = new TarArchiveInputStream(input)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                String normalized = normalizeEntryName(name);
                if (normalized == null || !matches(normalized, filters)) {
                    continue;
                }
                String shown = name.replace('\\', '/');
                if (entry.isDirectory() && !shown.endsWith("/")) {
                    shown = shown + "/";
                }
                reporter.detail(shown);
                entries++;
            }
        }

        reporter.info("tar: listed " + entries + " entries from "
                + FsUtils.quote(FsUtils.displayPath(reporter.source, archive)));
    }

    private static void diff(Path workspace, Path archive, Compression compression, List<String> filters,
                             Reporter reporter) throws IOException {
        if (!Files.isRegularFile(archive)) {
            throw new java.nio.file.NoSuchFileException(archive.toString());
        }

        Path base = workspace.toAbsolutePath().normalize();
        Compression actual = compression == Compression.NONE ? detectCompression(archive) : compression;
        int checked = 0;
        int differences = 0;

        try (InputStream file = Files.newInputStream(archive);
             InputStream input = wrapInput(file, actual);
             TarArchiveInputStream tar = new TarArchiveInputStream(input)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = normalizeEntryName(entry.getName());
                if (name == null || name.isEmpty() || !matches(name, filters)) {
                    continue;
                }

                Path target = base.resolve(name).normalize();
                if (!target.startsWith(base)) {
                    continue;
                }

                if (entry.isDirectory()) {
                    if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                        reporter.fail("tar: " + name + ": Warning: Cannot stat: No such file or directory");
                        differences++;
                    }
                    continue;
                }

                if (entry.isSymbolicLink() || entry.isLink()) {
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        reporter.fail("tar: " + name + ": Warning: Cannot stat: No such file or directory");
                        differences++;
                    }
                    continue;
                }

                if (!entry.isFile()) {
                    continue;
                }

                checked++;
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    reporter.fail("tar: " + name + ": Warning: Cannot stat: No such file or directory");
                    differences++;
                    continue;
                }
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    reporter.fail("tar: " + name + ": File type differs");
                    tar.transferTo(OutputStream.nullOutputStream());
                    differences++;
                    continue;
                }
                if (Files.size(target) != entry.getSize()) {
                    reporter.fail("tar: " + name + ": Size differs");
                    tar.transferTo(OutputStream.nullOutputStream());
                    differences++;
                    continue;
                }
                if (!contentEquals(tar, target)) {
                    reporter.fail("tar: " + name + ": Contents differ");
                    differences++;
                    continue;
                }

                FileTime modified = entry.getLastModifiedTime();
                long archivedSeconds = modified == null ? 0L : modified.toMillis() / 1000L;
                long fileSeconds = Files.getLastModifiedTime(target).toMillis() / 1000L;
                if (Math.abs(archivedSeconds - fileSeconds) >= 2L) {
                    reporter.fail("tar: " + name + ": Mod time differs");
                    differences++;
                }
            }
        }

        if (differences == 0) {
            reporter.info("tar: archive matches the file system (" + checked + " files checked)");
        } else {
            reporter.info("tar: " + differences + " difference(s) found");
        }
    }

    private static boolean contentEquals(InputStream archived, Path file) throws IOException {
        byte[] archiveBuffer = new byte[8192];
        byte[] fileBuffer = new byte[8192];
        boolean equal = true;

        try (InputStream other = Files.newInputStream(file)) {
            while (true) {
                int archiveRead = readBlock(archived, archiveBuffer);
                int fileRead = readBlock(other, fileBuffer);

                if (archiveRead != fileRead) {
                    equal = false;
                }
                int common = Math.min(archiveRead, fileRead);
                for (int i = 0; i < common; i++) {
                    if (archiveBuffer[i] != fileBuffer[i]) {
                        equal = false;
                        break;
                    }
                }
                if (archiveRead == 0 && fileRead == 0) {
                    return equal;
                }
            }
        }
    }

    private static int readBlock(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static int copyArchive(TarArchiveOutputStream tar, Path archive, Compression compression)
            throws IOException {
        int count = 0;
        try (InputStream file = Files.newInputStream(archive);
             InputStream input = wrapInput(file, compression);
             TarArchiveInputStream source = new TarArchiveInputStream(input)) {
            TarArchiveEntry entry;
            while ((entry = source.getNextEntry()) != null) {
                TarArchiveEntry copy = copyEntry(entry);
                tar.putArchiveEntry(copy);
                if (entry.isFile()) {
                    source.transferTo(tar);
                }
                tar.closeArchiveEntry();
                count++;
            }
        }
        return count;
    }

    /**
     * Commons Compress 1.28 has no copy constructor, so reproduce the fields
     * that matter for a faithful append.
     */
    private static TarArchiveEntry copyEntry(TarArchiveEntry entry) {
        String name = entry.getName();
        if (entry.isDirectory() && !name.endsWith("/")) {
            name = name + "/";
        }
        TarArchiveEntry copy = new TarArchiveEntry(name, entry.getLinkFlag());
        copy.setMode(entry.getMode());
        FileTime modified = entry.getLastModifiedTime();
        if (modified != null) {
            copy.setModTime(modified);
        }
        copy.setUserName(entry.getUserName());
        copy.setGroupName(entry.getGroupName());
        copy.setIds((int) entry.getLongUserId(), (int) entry.getLongGroupId());
        if (entry.isSymbolicLink() || entry.isLink()) {
            copy.setLinkName(entry.getLinkName());
        }
        if (entry.isFile()) {
            copy.setSize(entry.getSize());
        }
        return copy;
    }

    private static Map<String, Long> readModTimes(Path archive) throws IOException {
        Map<String, Long> modified = new HashMap<>();
        if (!Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            return modified;
        }

        Compression compression = detectCompression(archive);
        try (InputStream file = Files.newInputStream(archive);
             InputStream input = wrapInput(file, compression);
             TarArchiveInputStream tar = new TarArchiveInputStream(input)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = normalizeEntryName(entry.getName());
                if (entry.isDirectory() || name == null || name.isEmpty()) {
                    continue;
                }
                FileTime modTime = entry.getLastModifiedTime();
                modified.put(name, modTime == null ? 0L : modTime.toMillis());
            }
        }
        return modified;
    }

    private static boolean matches(String name, List<String> filters) {
        if (filters.isEmpty()) {
            return true;
        }

        String normalized = trimTrailingSlash(name.replace('\\', '/'));
        for (String filter : filters) {
            String candidate = trimTrailingSlash(filter.replace('\\', '/'));
            while (candidate.startsWith("./")) {
                candidate = candidate.substring(2);
            }
            if (candidate.isEmpty() || normalized.equals(candidate) || normalized.startsWith(candidate + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * Turns an archive member name into a safe relative path, or null when it
     * would escape the extraction root.
     */
    private static String normalizeEntryName(String rawName) {
        if (rawName == null) {
            return null;
        }

        String name = rawName.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            return null;
        }

        StringBuilder normalized = new StringBuilder();
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                return null;
            }
            if (!normalized.isEmpty()) {
                normalized.append('/');
            }
            normalized.append(part);
        }
        return normalized.toString();
    }

    private static boolean isSafeLink(Path base, Path target, String linkName) {
        if (linkName == null || linkName.isBlank()) {
            return false;
        }

        String normalized = linkName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            return false;
        }

        Path parent = target.getParent() == null ? base : target.getParent();
        return parent.resolve(normalized).normalize().startsWith(base);
    }

    private static boolean hasUnsafeAncestor(Path base, Path target) {
        Path current = target.getParent();
        while (current != null && !current.equals(base) && current.startsWith(base)) {
            if (Files.isSymbolicLink(current)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static void restoreTime(Path target, TarArchiveEntry entry) {
        FileTime modified = entry.getLastModifiedTime();
        if (modified == null) {
            return;
        }
        try {
            Files.setLastModifiedTime(target, modified);
        } catch (IOException ignored) {
            // Timestamps are best-effort.
        }
    }

    private static Compression detectCompression(Path archive) throws IOException {
        byte[] magic = new byte[6];
        try (InputStream input = Files.newInputStream(archive)) {
            int read = input.readNBytes(magic, 0, magic.length);

            if (read >= 2 && (magic[0] & 0xFF) == 0x1F && (magic[1] & 0xFF) == 0x8B) {
                return Compression.GZIP;
            }
            if (read >= 3 && magic[0] == 'B' && magic[1] == 'Z' && magic[2] == 'h') {
                return Compression.BZIP2;
            }
            if (read >= 6 && (magic[0] & 0xFF) == 0xFD && magic[1] == '7' && magic[2] == 'z'
                    && magic[3] == 'X' && magic[4] == 'Z' && magic[5] == 0x00) {
                return Compression.XZ;
            }
            if (read >= 5 && (magic[0] & 0xFF) == 0x5D && magic[1] == 0x00
                    && magic[2] == 0x00 && (magic[3] & 0xFF) == 0x80 && magic[4] == 0x00) {
                return Compression.LZMA;
            }
        }
        return Compression.NONE;
    }

    private static InputStream wrapInput(InputStream input, Compression compression) throws IOException {
        return switch (compression) {
            case NONE -> input;
            case GZIP -> GzipCompressorInputStream.builder()
                    .setInputStream(input)
                    .setDecompressConcatenated(true)
                    .get();
            case BZIP2 -> new BZip2CompressorInputStream(input, true);
            case XZ -> XZCompressorInputStream.builder()
                    .setInputStream(input)
                    .setDecompressConcatenated(true)
                    .get();
            case LZMA -> new LZMACompressorInputStream(input);
        };
    }

    private static OutputStream wrapOutput(OutputStream output, Compression compression) throws IOException {
        return switch (compression) {
            case NONE -> output;
            case GZIP -> new GzipCompressorOutputStream(output);
            case BZIP2 -> new BZip2CompressorOutputStream(output);
            case XZ -> new XZCompressorOutputStream(output);
            case LZMA -> new LZMACompressorOutputStream(output);
        };
    }

    private enum Compression {
        NONE,
        GZIP,
        BZIP2,
        XZ,
        LZMA
    }

    private record DeferredLink(Path path, String linkName, boolean hardLink) {
    }

    @FunctionalInterface
    private interface ArchiveWriter {
        int write(TarArchiveOutputStream tar) throws IOException;
    }

    /**
     * Streams progress back to the command source with a cap so the chat window
     * cannot be flooded by a large archive listing.
     */
    private static final class Reporter {
        private final CommandSourceStack source;
        private int lines;
        private int characters;
        private boolean truncated;

        private Reporter(CommandSourceStack source) {
            this.source = source;
        }

        private void info(String message) {
            send(message, false);
        }

        private void fail(String message) {
            send(message, true);
        }

        private void detail(String message) {
            if (truncated) {
                return;
            }

            String line = message.length() > MAX_LINE_LENGTH
                    ? message.substring(0, MAX_LINE_LENGTH) + "..."
                    : message;

            if (lines >= MAX_OUTPUT_LINES || characters + line.length() > MAX_OUTPUT_CHARS) {
                truncated = true;
                send("tar: output truncated (limit " + MAX_OUTPUT_LINES
                        + " lines / " + MAX_OUTPUT_CHARS + " characters)", false);
                return;
            }

            lines++;
            characters += line.length();
            send(line, false);
        }

        private void send(String message, boolean error) {
            MinecraftServer server = source.getServer();

            try {
                server.execute(() -> {
                    if (error) {
                        FsUtils.error(source, message);
                    } else {
                        FsUtils.success(source, message);
                    }
                });
            } catch (RejectedExecutionException exception) {
                // The server is shutting down; there is nowhere left to report output.
            }
        }
    }
}
