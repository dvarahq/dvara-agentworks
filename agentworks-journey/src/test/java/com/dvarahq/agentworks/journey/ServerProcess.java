package com.dvarahq.agentworks.journey;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One server process: the unmodified packaged boot jar, started exactly as an operator would start a Spring Boot jar
 * with an extension directory, {@code java -cp <boot jar> -Dloader.path=<extension> PropertiesLauncher}. Spring Boot's
 * {@code PropertiesLauncher} ships inside every boot jar and runs the jar's own {@code Start-Class}; the extension only
 * adds classes to the classpath. Properties are passed as command-line arguments. The process binds port 0, and the
 * harness reads the port it chose from its log.
 */
final class ServerProcess {
    static final String LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";
    private static final Pattern PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private static final Duration STARTUP = Duration.ofMinutes(3);

    final String name;
    final Process process;
    final Path log;
    final int port;

    private ServerProcess(String name, Process process, Path log, int port) {
        this.name = name;
        this.process = process;
        this.log = log;
        this.port = port;
    }

    static List<String> command(Path serverJar, Path extensionJar, Map<String, String> properties) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx768m");
        command.add("-cp");
        command.add(serverJar.toString());
        command.add("-Dloader.path=" + extensionJar);
        command.add(LAUNCHER);
        properties.forEach((key, value) -> command.add("--" + key + "=" + value));
        return command;
    }

    /** The packaged jar alone, as an operator runs it: {@code java -jar}, with no extension on any loader path. */
    static List<String> productCommand(Path serverJar, Map<String, String> properties) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx768m");
        command.add("-jar");
        command.add(serverJar.toString());
        properties.forEach((key, value) -> command.add("--" + key + "=" + value));
        return command;
    }

    static ServerProcess start(String name, List<String> command, Map<String, String> environment,
            Evidence evidence) {
        Process process = launch(name, command, environment, evidence);
        Path log = evidence.serverLog(name);
        long deadline = System.nanoTime() + STARTUP.toNanos();
        Integer port = null;
        while (System.nanoTime() < deadline) {
            String output = read(log);
            Matcher matcher = PORT.matcher(output);
            if (port == null && matcher.find()) port = Integer.parseInt(matcher.group(1));
            if (port != null && output.contains("Started DvaraAgentWorksApplication")) {
                return new ServerProcess(name, process, log, port);
            }
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited with " + process.exitValue() + " during startup:\n"
                        + tail(output));
            }
            sleep(100);
        }
        process.destroyForcibly();
        throw new IllegalStateException(name + " did not start within " + STARTUP + ":\n" + tail(read(log)));
    }

    /** Starts a server that must refuse to start, and returns its exit code and output. */
    static Refusal expectRefusal(String name, List<String> command, Map<String, String> environment,
            Evidence evidence) {
        Process process = launch(name, command, environment, evidence);
        try {
            long deadline = System.nanoTime() + STARTUP.toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (read(evidence.serverLog(name)).contains("Started DvaraAgentWorksApplication")
                        || System.nanoTime() > deadline) {
                    process.destroyForcibly();
                    throw new IllegalStateException(name + " was expected to refuse to start, but did not");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
        return new Refusal(process.exitValue(), read(evidence.serverLog(name)));
    }

    record Refusal(int exitCode, String output) {
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    long pid() {
        return process.pid();
    }

    /** Kills the process at once, as a crash or an OOM kill would: SIGKILL, no shutdown hooks. */
    int kill() {
        process.destroyForcibly();
        return await();
    }

    /** Stops the process gracefully (SIGTERM). */
    int stop() {
        process.destroy();
        return await();
    }

    String output() {
        return read(log);
    }

    private int await() {
        try {
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                process.waitFor(30, TimeUnit.SECONDS);
            }
            return process.exitValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static Process launch(String name, List<String> command, Map<String, String> environment,
            Evidence evidence) {
        evidence.command(name, command);
        Path log = evidence.serverLog(name);
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(evidence.work("").toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().putAll(environment);
            Process process = builder.start();
            // A server never outlives the harness, even when the test JVM is stopped early.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (process.isAlive()) process.destroyForcibly();
            }, "journey-" + name + "-reaper"));
            return process;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static String read(Path log) {
        try {
            return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String tail(String output) {
        return output.length() <= 8000 ? output : output.substring(output.length() - 8000);
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
