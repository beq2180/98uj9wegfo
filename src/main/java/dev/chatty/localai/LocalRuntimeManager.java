package dev.chatty.localai;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class LocalRuntimeManager {
    private static volatile Process process;
    private static volatile Thread logThread;
    private static volatile Integer lastExitCode;
    private static volatile String lastLaunchCommand = "";
    private static final Deque<String> recentOutput = new ArrayDeque<>();
    private static final int MAX_OUTPUT_LINES = 12;

    private LocalRuntimeManager() {}

    public static synchronized String start() {
        if (LocalAiClient.isServerReachable(1200)) {
            return "A compatible local AI server is already reachable at " + ConfigManager.get().baseUrl + ". Using it instead of starting another process.";
        }

        if (isRunning()) {
            return "Local model process is already running (PID " + process.pid() + ").";
        }

        String configuredCommand = ConfigManager.get().runtimeCommand;
        if (configuredCommand == null || configuredCommand.isBlank()) {
            return "No runtimeCommand is configured. Edit " + ConfigManager.path() + ".";
        }

        clearDiagnostics();

        String command = resolveRuntimeCommand(configuredCommand);
        lastLaunchCommand = command;

        try {
            Path workDir = FabricLoader.getInstance().getConfigDir().resolve("localai-runtime");
            Files.createDirectories(workDir);

            ProcessBuilder builder;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                builder = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                builder = new ProcessBuilder("/bin/sh", "-lc", command);
            }
            builder.directory(workDir.toFile());
            builder.redirectErrorStream(true);
            process = builder.start();
            startLogPump(process);

            // Give immediately-failing commands enough time to tell us why they died.
            try {
                if (process.waitFor(700, TimeUnit.MILLISECONDS)) {
                    lastExitCode = process.exitValue();
                    String detail = lastOutputSummary();
                    process = null;
                    return "Runtime exited immediately (code " + lastExitCode + ")" + (detail.isBlank() ? "." : ": " + detail);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            long pid = process.pid();
            return "Started local model process (PID " + pid + ") using: " + command + ". It may take a few seconds before the API is ready.";
        } catch (IOException e) {
            LocalAiMod.LOGGER.error("Failed to start local model process", e);
            addOutput(e.toString());
            process = null;
            return "Could not start the local model process: " + e.getMessage();
        }
    }

    private static String resolveRuntimeCommand(String configuredCommand) {
        String trimmed = configuredCommand.trim();
        if (!trimmed.equals("ollama serve")) {
            return configuredCommand;
        }

        if (!isMac()) {
            return configuredCommand;
        }

        String home = System.getProperty("user.home", "");
        String[] candidates = new String[] {
                "/Applications/Ollama.app/Contents/Resources/ollama",
                home + "/Applications/Ollama.app/Contents/Resources/ollama",
                home + "/Documents/Ollama.app/Contents/Resources/ollama",
                home + "/Downloads/Ollama.app/Contents/Resources/ollama",
                "/opt/homebrew/bin/ollama",
                "/usr/local/bin/ollama"
        };

        for (String candidate : candidates) {
            if (!candidate.isBlank() && Files.isExecutable(Path.of(candidate))) {
                return quote(candidate) + " serve";
            }
        }
        return configuredCommand;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void startLogPump(Process p) {
        logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    addOutput(line);
                    LocalAiMod.LOGGER.info("[AI runtime] {}", line);
                }
            } catch (IOException e) {
                if (p.isAlive()) {
                    LocalAiMod.LOGGER.warn("AI runtime log reader stopped unexpectedly", e);
                    addOutput("Log reader error: " + e.getMessage());
                }
            } finally {
                if (!p.isAlive()) {
                    try {
                        lastExitCode = p.exitValue();
                    } catch (IllegalThreadStateException ignored) {
                    }
                }
            }
        }, "localai-runtime-log");
        logThread.setDaemon(true);
        logThread.start();
    }

    private static synchronized void addOutput(String line) {
        if (line == null || line.isBlank()) return;
        while (recentOutput.size() >= MAX_OUTPUT_LINES) {
            recentOutput.removeFirst();
        }
        recentOutput.addLast(line.trim());
    }

    private static synchronized void clearDiagnostics() {
        recentOutput.clear();
        lastExitCode = null;
    }

    public static synchronized String stop() {
        if (!isRunning()) {
            process = null;
            return "No LocalAI process started by this mod is currently running.";
        }

        long pid = process.pid();
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            if (!process.isAlive()) {
                lastExitCode = process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
        return "Stopped local model process (PID " + pid + ").";
    }

    public static boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public static String status() {
        Process p = process;
        boolean reachable = LocalAiClient.isServerReachable(800);
        String runtime;
        if (p != null && p.isAlive()) {
            runtime = "running, pid=" + p.pid();
        } else {
            runtime = "not running";
            if (lastExitCode != null) runtime += ", lastExit=" + lastExitCode;
        }

        return "runtime=" + runtime
                + ", api=" + (reachable ? "reachable" : "unreachable")
                + ", provider=" + ConfigManager.get().provider
                + ", model=" + ConfigManager.get().model
                + ", url=" + ConfigManager.get().baseUrl;
    }

    public static String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append(status());
        if (!lastLaunchCommand.isBlank()) {
            sb.append("\nlaunch=").append(lastLaunchCommand);
        }
        String output = lastOutputSummary();
        if (!output.isBlank()) {
            sb.append("\nlast output: ").append(output);
        }
        if (!LocalAiClient.isServerReachable(800)) {
            sb.append("\nTip: if the last output says 'ollama: command not found', install/configure Ollama or set runtimeCommand to its full path. If it says 'address already in use', an Ollama server is probably already running.");
        }
        return sb.toString();
    }

    private static synchronized String lastOutputSummary() {
        if (recentOutput.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String line : recentOutput) {
            if (count++ > 0) sb.append(" | ");
            sb.append(line);
        }
        return sb.toString();
    }
}
