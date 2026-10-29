package com.dvarahq.agentworks.testing;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;

final class AgentTestReporter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Path directory;
    AgentTestReporter() { this(Path.of(System.getProperty("agentworks.agenttest.reportDirectory", "target/agent-test-reports"))); }
    AgentTestReporter(Path directory) { this.directory = Objects.requireNonNull(directory); }
    Path write(AgentTestReport report) {
        try {
            Files.createDirectories(directory);
            Path output = directory.resolve(safe(report.testId()) + ".json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            return output;
        } catch (IOException failure) {
            throw new IllegalStateException("AGENT_TEST_REPORT_WRITE_FAILED", failure);
        }
    }
    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }
}
