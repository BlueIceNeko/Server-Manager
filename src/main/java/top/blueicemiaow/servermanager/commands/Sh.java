package top.blueicemiaow.servermanager.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import top.blueicemiaow.servermanager.tools.FsUtils;
import top.blueicemiaow.servermanager.tools.WorkspaceHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs an executable directly in the issuer's current workspace without going
 * through a shell. Shell built-ins, pipes, redirection, variable expansion,
 * globbing and other shell syntax are therefore not interpreted. Quotes are
 * accepted only to group arguments. Output is streamed back to the command
 * source and capped so a chat window cannot be flooded.
 */
public class Sh {
    private static final String USAGE = "usage: sh <program> [args ...]";
    private static final int MAX_OUTPUT_LINES = 200;
    private static final int MAX_OUTPUT_CHARS = 20000;
    private static final int MAX_LINE_LENGTH = 1000;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, Commands.CommandSelection environment) {
        dispatcher.register(Commands.literal("sh")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(context -> execute(
                                context.getSource(),
                                StringArgumentType.getString(context, "command"))))
                .executes(context -> {
                    FsUtils.error(context.getSource(), "sh: missing command");
                    FsUtils.success(context.getSource(), USAGE);
                    return 0;
                }));
    }

    private static int execute(CommandSourceStack source, String command) {
        List<String> commandLine;
        try {
            commandLine = FsUtils.tokenize(command == null ? "" : command.trim());
        } catch (IllegalArgumentException exception) {
            FsUtils.error(source, "sh: " + exception.getMessage());
            FsUtils.success(source, USAGE);
            return 0;
        }

        if (commandLine.isEmpty()) {
            FsUtils.error(source, "sh: missing command");
            FsUtils.success(source, USAGE);
            return 0;
        }

        Path workspace = WorkspaceHelper.getWorkspace(source);

        // Never block the server thread while waiting for an external process.
        Thread worker = new Thread(() -> run(source, workspace, commandLine), "ServerManager-sh");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static void run(CommandSourceStack source, Path workspace, List<String> commandLine) {
        Process process = null;

        try {
            ProcessBuilder builder = new ProcessBuilder(commandLine)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true);

            process = builder.start();

            int lines = 0;
            int characters = 0;
            boolean truncated = false;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), outputCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > MAX_LINE_LENGTH) {
                        line = line.substring(0, MAX_LINE_LENGTH) + "...";
                    }
                    if (lines < MAX_OUTPUT_LINES && characters + line.length() <= MAX_OUTPUT_CHARS) {
                        send(source, line);
                        lines++;
                        characters += line.length();
                    } else if (!truncated) {
                        truncated = true;
                        send(source, "sh: output truncated (limit " + MAX_OUTPUT_LINES
                                + " lines / " + MAX_OUTPUT_CHARS + " characters)");
                    }
                }
            }

            int exitCode = process.waitFor();
            send(source, "sh: exit code " + exitCode);
        } catch (IOException exception) {
            send(source, "sh: failed to execute: " + FsUtils.reason(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            send(source, "sh: interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static Charset outputCharset() {
        String nativeEncoding = System.getProperty("native.encoding");
        if (nativeEncoding != null && !nativeEncoding.isBlank()) {
            try {
                return Charset.forName(nativeEncoding);
            } catch (RuntimeException ignored) {
                // Fall back to the JVM default below.
            }
        }
        return Charset.defaultCharset();
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
}
