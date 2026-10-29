package com.dvarahq.agentworks.testing;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/** Fluent expectation set. Every mismatch has a stable code and is persisted before it is thrown. */
public final class AgentTestRun {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)
                    .block(iri -> !iri.toString().startsWith("https://json-schema.org/draft/2020-12/"))));
    private final AgentTestResult result;
    private final AgentTestReport report;
    private final AgentTestReporter reporter;
    private Path reportPath;
    AgentTestRun(AgentTestResult result, AgentTestReport report, AgentTestReporter reporter) {
        this.result = result; this.report = report; this.reporter = reporter; this.reportPath = reporter.write(report);
    }
    public AgentTestRun expectStatus(AgentTestStatus expected) { check("STATUS_MISMATCH", expected, result.status()); return this; }
    public AgentTestRun expectAnswerContains(String text) {
        if (!result.answer().contains(text)) fail("ANSWER_MISMATCH", "contains " + text, result.answer()); return this;
    }
    public AgentTestRun expectTool(String name) { requireEvent("TOOL_MISSING", AgentTestEvent.Type.TOOL_REQUESTED, name, true); return this; }
    public AgentTestRun expectNoTool(String name) { requireEvent("UNEXPECTED_TOOL", AgentTestEvent.Type.TOOL_REQUESTED, name, false); return this; }
    public AgentTestRun expectNoToolExecution(String name) { requireEvent("UNEXPECTED_TOOL_EXECUTION", AgentTestEvent.Type.TOOL_EXECUTED, name, false); return this; }
    public AgentTestRun expectHumanInteraction(String kind) { requireEvent("INTERACTION_MISSING", AgentTestEvent.Type.HUMAN_INTERACTION, kind, true); return this; }
    public AgentTestRun expectNoHumanInteraction() {
        boolean found = result.events().stream().anyMatch(event -> event.type() == AgentTestEvent.Type.HUMAN_INTERACTION);
        if (found) fail("UNEXPECTED_INTERACTION", "none", matching(AgentTestEvent.Type.HUMAN_INTERACTION).toString()); return this;
    }
    public AgentTestRun expectPolicyDecision(String decision) { requireEvent("POLICY_DECISION_MISSING", AgentTestEvent.Type.POLICY_DECISION, decision, true); return this; }
    public AgentTestRun expectArtifact(String name) { requireEvent("ARTIFACT_MISSING", AgentTestEvent.Type.ARTIFACT, name, true); return this; }
    public AgentTestRun expectTrace(String... names) {
        List<String> expected = List.of(names);
        List<String> actual = result.events().stream().map(AgentTestEvent::name).toList();
        if (!actual.equals(expected)) fail("TRACE_MISMATCH", expected, actual);
        return this;
    }
    public AgentTestRun expectMaxToolCalls(int maximum) { if (maximum < 0) throw new IllegalArgumentException("maximum is negative"); if (result.usage().toolCalls() > maximum) fail("TOOL_BUDGET_EXCEEDED", "<= " + maximum, result.usage().toolCalls()); return this; }
    public AgentTestRun expectMaxTokens(long maximum) { if (maximum < 0) throw new IllegalArgumentException("maximum is negative"); if (result.usage().totalTokens() > maximum) fail("TOKEN_BUDGET_EXCEEDED", "<= " + maximum, result.usage().totalTokens()); return this; }
    public AgentTestRun expectMaxCost(BigDecimal maximum) { Objects.requireNonNull(maximum, "maximum"); if (maximum.signum() < 0) throw new IllegalArgumentException("maximum is negative"); if (result.usage().cost().compareTo(maximum) > 0) fail("COST_BUDGET_EXCEEDED", "<= " + maximum, result.usage().cost()); return this; }
    public AgentTestRun expectStructuredOutput(String schemaJson) {
        if (result.structuredOutput() == null) fail("STRUCTURED_OUTPUT_MISSING", "JSON output", "null");
        try {
            JsonNode schemaNode = JSON.readTree(schemaJson);
            selfContained(schemaNode);
            var schema = SCHEMAS.getSchema(schemaNode);
            var errors = schema.validate(result.structuredOutput());
            if (!errors.isEmpty()) fail("STRUCTURED_OUTPUT_MISMATCH", "matches schema", errors.toString());
        } catch (AgentTestAssertionError failure) { throw failure; }
        catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IllegalArgumentException("invalid JSON schema", invalid); }
        return this;
    }
    public AgentTestResult result() { return result; }
    public Path reportPath() { return reportPath; }
    private void requireEvent(String code, AgentTestEvent.Type type, String name, boolean expected) {
        boolean found = result.events().stream().anyMatch(event -> event.type() == type && event.name().equals(name));
        if (found != expected) fail(code, expected ? name : "not " + name, matching(type).toString());
    }
    private static void selfContained(JsonNode node) {
        if (node.isObject()) {
            for (var property : node.properties()) {
                if (property.getKey().equals("$ref") || property.getKey().equals("$dynamicRef")) {
                    String reference = property.getValue().isString() ? property.getValue().stringValue() : "";
                    if (!reference.startsWith("#")) throw new IllegalArgumentException("schema must be self-contained");
                }
                selfContained(property.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode value : node.values()) selfContained(value);
        }
    }
    private List<String> matching(AgentTestEvent.Type type) { return result.events().stream().filter(e -> e.type() == type).map(AgentTestEvent::name).toList(); }
    private void check(String code, Object expected, Object actual) { if (!Objects.equals(expected, actual)) fail(code, expected, actual); }
    private void fail(String code, Object expected, Object actual) {
        AgentTestMismatch mismatch = new AgentTestMismatch(code, String.valueOf(expected), String.valueOf(actual));
        reportPath = reporter.write(new AgentTestReport(report.version(), report.testId(), report.agent(), report.workspace(), report.seed(),
                report.generatedAt(), report.fixtureDigest(), report.evaluatorIdentity(), report.dependencies(), report.result(), report.traceDigest(),
                report.simulation(), List.of(mismatch)));
        throw new AgentTestAssertionError(mismatch);
    }
}
