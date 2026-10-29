package com.dvarahq.agentworks.journey;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The journey's archived evidence, written under {@code journey.evidence.dir} (CI uploads the directory):
 * <ul>
 *   <li>{@code commands.log}: every server command line the harness ran, with secrets masked;</li>
 *   <li>{@code servers/<name>.log}: each server process's own output;</li>
 *   <li>{@code transcripts/}: raw SSE transcripts and the verified event pages of each journey's runs;</li>
 *   <li>{@code restart-injection.json}: the kill, its timing, and the run's state before and after;</li>
 *   <li>{@code artifacts.json} and {@code environment.json}: the exact artifact and environment identities;</li>
 *   <li>{@code report.json}: every journey's PASS/FAIL, with its facts.</li>
 * </ul>
 */
final class Evidence {
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SECRET = Pattern.compile("(--[^=\\s]*(password|token|hmac-keys)[^=\\s]*=)\\S+",
            Pattern.CASE_INSENSITIVE);

    final Path root;
    private final Map<String, Map<String, Object>> journeys = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> facts = new LinkedHashMap<>();

    Evidence(Path root) {
        this.root = root;
        try {
            if (Files.exists(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
            Files.createDirectories(root.resolve("servers"));
            Files.createDirectories(root.resolve("transcripts"));
            Files.createDirectories(root.resolve("work"));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    Path work(String name) {
        return root.resolve("work").resolve(name);
    }

    Path serverLog(String name) {
        return root.resolve("servers").resolve(name + ".log");
    }

    void command(String name, List<String> command) {
        String line = Instant.now() + " " + name + ": " + SECRET.matcher(String.join(" ", command)).replaceAll("$1****");
        append(root.resolve("commands.log"), line + "\n");
    }

    void transcript(String file, String content) {
        append(root.resolve("transcripts").resolve(file), content);
    }

    void json(String file, Object value) {
        write(root.resolve(file), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    void events(String file, List<JsonNode> events) {
        write(root.resolve("transcripts").resolve(file),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(events) + "\n");
    }

    /** Records a fact of the current journey for the report. */
    synchronized void fact(String journey, String key, Object value) {
        facts.computeIfAbsent(journey, ignored -> new LinkedHashMap<>()).put(key, value);
    }

    synchronized void writeReport(Map<String, Object> header) {
        Map<String, Object> report = new LinkedHashMap<>(header);
        List<Map<String, Object>> results = new ArrayList<>();
        journeys.forEach((name, result) -> {
            Map<String, Object> entry = new LinkedHashMap<>(result);
            entry.put("facts", facts.getOrDefault(name, Map.of()));
            results.add(entry);
        });
        report.put("journeys", results);
        Object expected = header.get("expectedJourneys");
        boolean passed = !results.isEmpty() && results.stream().allMatch(result -> "PASS".equals(result.get("result")))
                && (expected == null || expected.equals(results.size()));
        report.put("verdict", passed ? header.getOrDefault("verdictIfAllJourneysPass", "PASS") : "FAIL");
        json("report.json", report);
    }

    private synchronized void journey(ExtensionContext context, String result, Throwable cause) {
        String name = context.getRequiredTestMethod().getName();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("journey", name);
        entry.put("displayName", context.getDisplayName());
        entry.put("result", result);
        if (cause != null) entry.put("failure", cause.getClass().getName() + ": " + cause.getMessage());
        journeys.put(name, entry);
    }

    /** Records each journey's outcome. */
    TestWatcher watcher() {
        return new TestWatcher() {
            @Override
            public void testSuccessful(ExtensionContext context) {
                journey(context, "PASS", null);
            }

            @Override
            public void testFailed(ExtensionContext context, Throwable cause) {
                journey(context, "FAIL", cause);
            }

            @Override
            public void testAborted(ExtensionContext context, Throwable cause) {
                journey(context, "FAIL", cause);
            }

            @Override
            public void testDisabled(ExtensionContext context, Optional<String> reason) {
                journey(context, "FAIL", new IllegalStateException("disabled: " + reason.orElse("")));
            }
        };
    }

    private static void append(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
