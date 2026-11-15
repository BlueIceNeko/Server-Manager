package top.blueicemiaow.servermanager.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import top.blueicemiaow.servermanager.tools.ArgParser;
import top.blueicemiaow.servermanager.tools.FsUtils;
import top.blueicemiaow.servermanager.tools.WorkspaceHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately small HTTP/HTTPS downloader. It implements only -h, -O, -P,
 * -t and -T (and their long forms); it is not GNU Wget and has far fewer features.
 */
public class WGet {
    private static final String NOTICE = "wget: feature-limited built-in downloader, NOT GNU Wget and not affiliated with GNU Wget.";
    private static final String USAGE = "usage: wget [-h] [-O file] [-P dir] [-t tries] [-T seconds] URL ...";
    private static final String HELP = """
            -h, --help                show this help and exit
            -O, --output-document=F   write all downloaded data to F (use '-' for text in chat)
            -P, --directory-prefix=D  save inferred file names under directory D
            -t, --tries=N             number of tries, 0 means forever (default 3)
            -T, --timeout=S           network timeout in seconds, 0 disables it (default 30)

            Only these options are implemented. This is not GNU Wget; many GNU Wget options and behaviours are deliberately absent.""";

    private static final Set<String> LONG_FLAGS = Set.of("help");
    private static final Set<String> LONG_VALUE_OPTIONS =
            Set.of("output-document", "directory-prefix", "tries", "timeout");

    private static final int DEFAULT_TRIES = 3;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final long RETRY_DELAY_MILLIS = 1000L;

    private static final int MAX_INLINE_LINES = 200;
    private static final int MAX_INLINE_CHARS = 20000;
    private static final int MAX_INLINE_LINE_LENGTH = 1000;

    private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset\\s*=\\s*\"?([^\";\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("wget")
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
            FsUtils.error(source, "wget: " + exception.getMessage());
            return 0;
        }

        ArgParser arguments = new ArgParser(tokens, "OPtT", LONG_FLAGS, LONG_VALUE_OPTIONS);

        if (arguments.hasLong("help") || arguments.has('h')) {
            FsUtils.success(source, USAGE);
            for (String line : HELP.split("\\R")) {
                FsUtils.success(source, line);
            }
            return 1;
        }

        String invalidFlags = arguments.flagsOnly("h");
        String invalidValues = arguments.valueOptionsOnly("OPtT");
        if (!invalidFlags.isEmpty() || !invalidValues.isEmpty() || !arguments.unknown().isEmpty()) {
            String invalid = !invalidFlags.isEmpty()
                    ? invalidFlags
                    : !invalidValues.isEmpty() ? invalidValues : arguments.unknownOptions();
            FsUtils.error(source, "wget: invalid option -- " + invalid);
            FsUtils.success(source, USAGE);
            return 0;
        }

        if (arguments.operands().isEmpty()) {
            FsUtils.error(source, "wget: missing URL");
            FsUtils.success(source, USAGE);
            return 0;
        }

        String outputDocument = option(arguments, 'O', "output-document");
        String prefixArgument = option(arguments, 'P', "directory-prefix");
        String triesArgument = option(arguments, 't', "tries");
        String timeoutArgument = option(arguments, 'T', "timeout");

        if (outputDocument != null && outputDocument.isEmpty()) {
            FsUtils.error(source, "wget: empty output document");
            return 0;
        }

        int tries;
        try {
            tries = parseTries(triesArgument);
        } catch (IllegalArgumentException exception) {
            FsUtils.error(source, "wget: invalid tries value " + FsUtils.quote(String.valueOf(triesArgument)));
            return 0;
        }

        Duration timeout;
        try {
            timeout = parseTimeout(timeoutArgument);
        } catch (IllegalArgumentException exception) {
            FsUtils.error(source, "wget: invalid timeout value " + FsUtils.quote(String.valueOf(timeoutArgument)));
            return 0;
        }

        Path workspace = WorkspaceHelper.getWorkspace(source);
        Path prefix = prefixArgument == null ? workspace : FsUtils.resolve(workspace, prefixArgument);
        List<String> urls = new ArrayList<>(arguments.operands());

        Thread worker = new Thread(
                () -> download(source, workspace, prefix, outputDocument, urls, tries, timeout),
                "ServerManager-wget");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static String option(ArgParser arguments, char shortName, String longName) {
        String longValue = arguments.longValue(longName);
        return longValue != null ? longValue : arguments.value(shortName);
    }

    private static int parseTries(String value) {
        if (value == null) {
            return DEFAULT_TRIES;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("inf") || normalized.equals("infinite")) {
            return 0;
        }

        int tries;
        try {
            tries = Integer.parseInt(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid tries");
        }
        if (tries < 0) {
            throw new IllegalArgumentException("negative tries");
        }
        return tries;
    }

    private static Duration parseTimeout(String value) {
        if (value == null) {
            return Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
        }

        int seconds;
        try {
            seconds = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid timeout");
        }
        if (seconds < 0) {
            throw new IllegalArgumentException("negative timeout");
        }
        return seconds == 0 ? null : Duration.ofSeconds(seconds);
    }

    private static void download(CommandSourceStack source, Path workspace, Path prefix, String outputDocument,
                                 List<String> urls, int tries, Duration timeout) {
        HttpClient client = createClient(timeout);
        boolean inline = "-".equals(outputDocument);

        Path outputFile = null;
        OutputStream outputStream = null;
        long totalBytes = 0L;
        int successes = 0;
        int failures = 0;

        if (outputDocument != null && !inline) {
            outputFile = FsUtils.resolve(workspace, outputDocument);
            try {
                Path parent = outputFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                outputStream = Files.newOutputStream(outputFile,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                send(source, "wget: saving to " + FsUtils.quote(display(workspace, outputFile)));
            } catch (IOException exception) {
                send(source, "wget: cannot open " + FsUtils.quote(outputFile.toString())
                        + ": " + FsUtils.reason(exception));
                return;
            }
        }

        try {
            for (String url : urls) {
                send(source, "wget: downloading " + url);

                URI uri;
                try {
                    uri = URI.create(url);
                    String scheme = uri.getScheme();
                    if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                        throw new IOException("unsupported URL scheme");
                    }
                } catch (IllegalArgumentException exception) {
                    send(source, "wget: " + url + ": invalid URL");
                    failures++;
                    continue;
                } catch (IOException exception) {
                    send(source, "wget: " + url + ": " + exception.getMessage());
                    failures++;
                    continue;
                }

                try {
                    if (inline) {
                        FetchResponse response = fetch(client, uri, timeout, tries);
                        try (InputStream body = response.body()) {
                            sendInline(source, body, response.charset());
                        }
                        successes++;
                        send(source, "wget: downloaded " + url);
                    } else if (outputStream != null) {
                        FetchResponse response = fetch(client, uri, timeout, tries);
                        try (InputStream body = response.body()) {
                            totalBytes += body.transferTo(outputStream);
                        }
                        successes++;
                    } else {
                        Files.createDirectories(prefix);
                        Path target = uniqueTarget(prefix, fileName(uri));
                        Path temp = Files.createTempFile(prefix, ".wget-", ".part");
                        long bytes;
                        try {
                            FetchResponse response = fetch(client, uri, timeout, tries);
                            try (InputStream body = response.body();
                                 OutputStream file = Files.newOutputStream(temp,
                                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                                bytes = body.transferTo(file);
                            }
                            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException | RuntimeException exception) {
                            Files.deleteIfExists(temp);
                            throw exception;
                        }
                        successes++;
                        totalBytes += bytes;
                        send(source, "wget: saved " + FsUtils.quote(display(workspace, target))
                                + " (" + bytes + " bytes)");
                    }
                } catch (HttpStatusException exception) {
                    send(source, "wget: " + url + ": " + exception.getMessage());
                    failures++;
                } catch (IOException exception) {
                    send(source, "wget: " + url + ": " + FsUtils.reason(exception));
                    failures++;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    send(source, "wget: interrupted");
                    failures++;
                    break;
                }
            }
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException ignored) {
                    // Nothing more can be reported here.
                }
            }
        }

        if (outputStream != null && successes > 0) {
            send(source, "wget: saved " + FsUtils.quote(display(workspace, outputFile))
                    + " (" + totalBytes + " bytes)");
        }
        if (failures > 0) {
            send(source, "wget: finished with " + failures + " error(s)");
        }
    }

    private static HttpClient createClient(Duration timeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (timeout != null) {
            builder.connectTimeout(timeout);
        }
        return builder.build();
    }

    private static FetchResponse fetch(HttpClient client, URI uri, Duration timeout, int tries)
            throws IOException, InterruptedException {
        int attempt = 0;

        while (true) {
            attempt++;

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .GET()
                    .header("User-Agent", "ServerManager-minimal-wget/1.0");
            if (timeout != null) {
                builder.timeout(timeout);
            }

            try {
                HttpResponse<InputStream> response = client.send(
                        builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                if (status >= 400) {
                    response.body().close();
                    if (status >= 500 && canRetry(attempt, tries)) {
                        sleepBeforeRetry();
                        continue;
                    }
                    throw new HttpStatusException("HTTP " + status);
                }

                return new FetchResponse(response.body(), charsetOf(response));
            } catch (HttpStatusException exception) {
                throw exception;
            } catch (IOException exception) {
                if (!canRetry(attempt, tries)) {
                    throw exception;
                }
                sleepBeforeRetry();
            }
        }
    }

    private static boolean canRetry(int attempt, int tries) {
        return tries == 0 || attempt < tries;
    }

    private static void sleepBeforeRetry() throws InterruptedException {
        Thread.sleep(RETRY_DELAY_MILLIS);
    }

    private static Charset charsetOf(HttpResponse<?> response) {
        Optional<String> contentType = response.headers().firstValue("Content-Type");
        if (contentType.isPresent()) {
            Matcher matcher = CHARSET_PATTERN.matcher(contentType.get());
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1));
                } catch (RuntimeException ignored) {
                    // Fall back to UTF-8.
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static void sendInline(CommandSourceStack source, InputStream input, Charset charset) throws IOException {
        int lines = 0;
        int characters = 0;
        boolean truncated = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_INLINE_LINE_LENGTH) {
                    line = line.substring(0, MAX_INLINE_LINE_LENGTH) + "...";
                }

                if (lines < MAX_INLINE_LINES && characters + line.length() <= MAX_INLINE_CHARS) {
                    send(source, line);
                    lines++;
                    characters += line.length();
                } else if (!truncated) {
                    truncated = true;
                    send(source, "wget: output truncated (limit " + MAX_INLINE_LINES
                            + " lines / " + MAX_INLINE_CHARS + " characters)");
                }
            }
        }
    }

    private static Path uniqueTarget(Path directory, String name) {
        Path candidate = directory.resolve(name);
        int index = 1;
        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = directory.resolve(name + "." + index);
            index++;
        }
        return candidate;
    }

    private static String fileName(URI uri) {
        String path = uri.getPath();
        String name = path == null || path.isEmpty()
                ? "index.html"
                : path.substring(path.lastIndexOf('/') + 1);

        if (name.isEmpty()) {
            name = "index.html";
        }

        StringBuilder sanitized = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (character == '\\' || character == '/' || character == ':' || character == '*'
                    || character == '?' || character == '"' || character == '<' || character == '>'
                    || character == '|' || character < 0x20) {
                sanitized.append('_');
            } else {
                sanitized.append(character);
            }
        }

        name = sanitized.toString().strip();
        while (name.endsWith(".") || name.endsWith(" ")) {
            name = name.substring(0, name.length() - 1);
        }

        if (name.isEmpty() || name.equals("..")) {
            name = "index.html";
        }

        String base = name;
        int dot = base.indexOf('.');
        if (dot >= 0) {
            base = base.substring(0, dot);
        }
        String upper = base.toUpperCase(Locale.ROOT);
        if (WINDOWS_RESERVED_NAMES.contains(upper)) {
            name = "_" + name;
        }

        return name;
    }

    private static String display(Path workspace, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.startsWith(workspace) && !absolute.equals(workspace)) {
            return workspace.relativize(absolute).toString();
        }
        return absolute.toString();
    }

    /**
     * Command sources belong to the server thread, so hop back onto it before sending.
     */
    private static void send(CommandSourceStack source, String message) {
        MinecraftServer server = source.getServer();

        try {
            server.execute(() -> FsUtils.success(source, message));
        } catch (RejectedExecutionException exception) {
            // The server is shutting down; there is nowhere left to report output.
        }
    }

    private record FetchResponse(InputStream body, Charset charset) {
    }

    private static final class HttpStatusException extends IOException {
        private HttpStatusException(String message) {
            super(message);
        }
    }
}
