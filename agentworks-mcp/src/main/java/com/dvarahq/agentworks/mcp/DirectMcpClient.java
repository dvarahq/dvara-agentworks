package com.dvarahq.agentworks.mcp;

import com.dvarahq.agentworks.agent.springai.*;
import com.dvarahq.agentworks.capability.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Stateless direct MCP boundary. Each discovery or invocation creates a session from exact immutable pins; issue #77
 * owns shared client pooling. SDK and transport types remain behind {@link SessionFactory}.
 */
public final class DirectMcpClient implements McpConnectionHealthSource {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private final Identity identity;
    private final Policy policy;
    private final AccessPolicy access;
    private final SessionFactory sessions;
    private final EventSink events;
    private final Counter successes;
    private final Counter failures;
    private final AtomicReference<Health> health = new AtomicReference<>(new Health(Status.NEW, "MCP_NOT_PROBED", Instant.EPOCH, 0));

    public DirectMcpClient(Identity identity, Policy policy, AccessPolicy access, SessionFactory sessions,
            EventSink events, MeterRegistry meters) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.access = Objects.requireNonNull(access, "access");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.events = events == null ? ignored -> { } : events;
        this.successes = meters == null ? null : Counter.builder("agentworks.mcp.operations")
                .tag("connection", identity.connection()).tag("outcome", "success").register(meters);
        this.failures = meters == null ? null : Counter.builder("agentworks.mcp.operations")
                .tag("connection", identity.connection()).tag("outcome", "failure").register(meters);
    }

    public Identity identity() { return identity; }
    public Health health() { return health.get(); }
    @Override public McpConnectionHealth mcpConnectionHealth() {
        Health current = health();
        return new McpConnectionHealth(new com.dvarahq.agentworks.domain.tenant.WorkspaceId(identity.workspaceId()),
                identity.connection(), identity.version(), identity.releaseId(), identity.deploymentRevisionId(),
                identity.cohortId(), current.status().name(), current.code(), current.observedAt(), current.failures());
    }

    /** Discovery is separately policy-gated from invocation. Credential material is borrowed and never retained. */
    public Catalog discover(char[] credentialMaterial) {
        Discovery raw = operation("DISCOVER", null, credentialMaterial, true, Session::discover);
        List<RegisteredMcpTool> tools = raw.tools().stream()
                .filter(tool -> policy.discoverTools().contains(tool.name()))
                .filter(tool -> access.allow(identity, Action.DISCOVER, Kind.TOOL, tool.name()))
                .map(this::register).sorted(Comparator.comparing(tool -> tool.metadata().id().value())).toList();
        List<Resource> resources = raw.resources().stream()
                .filter(resource -> policy.discoverResources().contains(resource.uri()))
                .filter(resource -> access.allow(identity, Action.DISCOVER, Kind.RESOURCE, resource.uri())).toList();
        List<Prompt> prompts = raw.prompts().stream()
                .filter(prompt -> policy.discoverPrompts().contains(prompt.name()))
                .filter(prompt -> access.allow(identity, Action.DISCOVER, Kind.PROMPT, prompt.name())).toList();
        emit(new Event("MCP_DISCOVERED", identity, null, "MCP_OK", Instant.now()));
        return new Catalog(tools, resources, prompts);
    }

    public String readResource(String uri, char[] credentialMaterial) {
        requireAllowed(Action.INVOKE, Kind.RESOURCE, uri, policy.invokeResources());
        return bounded(operation("READ_RESOURCE", uri, credentialMaterial, false, session -> session.readResource(uri)));
    }

    public String getPrompt(String name, Map<String, Object> arguments, char[] credentialMaterial) {
        requireAllowed(Action.INVOKE, Kind.PROMPT, name, policy.invokePrompts());
        Map<String, Object> safe = boundedArguments(arguments);
        return bounded(operation("GET_PROMPT", name, credentialMaterial, false, session -> session.getPrompt(name, safe)));
    }

    private RegisteredMcpTool register(RemoteTool remote) {
        if (!NAME.matcher(remote.name()).matches()) throw failure("MCP_DISCOVERY_INVALID");
        String schema = canonicalSchema(remote.inputSchema());
        ToolSchemaContract.validate(schema);
        ToolId id = policy.toolIdentities().get(remote.name());
        if (id == null) throw failure("MCP_TOOL_IDENTITY_REQUIRED");
        ToolEffect effect = policy.toolEffects().get(remote.name());
        if (effect == null) throw failure("MCP_TOOL_EFFECT_REQUIRED");
        ToolDefinition definition = ToolDefinition.builder().name(remote.name())
                .description(description(remote.description())).inputSchema(schema).build();
        ToolCallback callback;
        CapabilityToolCallback binding;
        if (policy.credentialName().isPresent()) {
            callback = new CredentialedRemoteTool(definition, remote.name(), schema);
            binding = new CapabilityToolCallback(id, callback, policy.credentialName().get());
        } else {
            callback = new RemoteToolCallback(definition, remote.name(), schema);
            binding = new CapabilityToolCallback(id, callback);
        }
        AgentTool metadata = new AgentTool(id, ToolType.MCP, description(remote.description()),
                description(remote.description()), schema, AgentTool.digest(schema), effect, Set.of("mcp", identity.connection()),
                Set.of("mcp-direct-v1"), 64, 1024, AgentTool.Availability.ACTIVE);
        return new RegisteredMcpTool(metadata, binding, identity);
    }

    private final class RemoteToolCallback implements ArtifactToolCallback {
        private final ToolDefinition definition;
        private final String remoteName;
        private final String pinnedSchema;
        private RemoteToolCallback(ToolDefinition definition, String remoteName, String pinnedSchema) {
            this.definition = definition; this.remoteName = remoteName; this.pinnedSchema = pinnedSchema;
        }
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public boolean requiresArtifact(String output) { return true; }
        @Override public String call(String input) { return invokeTool(remoteName, pinnedSchema, input, null); }
        @Override public String call(String input, ToolContext context) { return call(input); }
    }

    private final class CredentialedRemoteTool implements CredentialedToolCallback, ArtifactToolCallback {
        private final ToolDefinition definition;
        private final String remoteName;
        private final String pinnedSchema;
        private CredentialedRemoteTool(ToolDefinition definition, String remoteName, String pinnedSchema) {
            this.definition = definition; this.remoteName = remoteName; this.pinnedSchema = pinnedSchema;
        }
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public boolean requiresArtifact(String output) { return true; }
        @Override public String call(String input, ToolContext context, char[] credentialMaterial) {
            return invokeTool(remoteName, pinnedSchema, input, credentialMaterial);
        }
    }

    private String invokeTool(String remoteName, String pinnedSchema, String input, char[] credentialMaterial) {
        requireAllowed(Action.INVOKE, Kind.TOOL, remoteName, policy.invokeTools());
        Map<String, Object> arguments = parseArguments(input);
        return bounded(operation("CALL_TOOL", remoteName, credentialMaterial, false, session -> {
            RemoteTool current = session.discover().tools().stream().filter(tool -> tool.name().equals(remoteName))
                    .findFirst().orElseThrow(() -> failure("MCP_TOOL_UNAVAILABLE"));
            if (!canonicalSchema(current.inputSchema()).equals(pinnedSchema)) {
                emit(new Event("MCP_SCHEMA_DRIFT", identity, remoteName, "MCP_SCHEMA_DRIFT", Instant.now()));
                throw failure(policy.driftPolicy() == DriftPolicy.FAIL_CLOSED
                        ? "MCP_SCHEMA_DRIFT" : "MCP_CAPABILITY_DEGRADED");
            }
            return session.callTool(remoteName, arguments);
        }));
    }

    private <T> T operation(String operation, String capability, char[] credential, boolean retryable,
            SessionCall<T> call) {
        int attempts = retryable ? policy.discoveryAttempts() : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try (Session session = sessions.open(identity, credential)) {
                T result = timed(() -> { session.initialize(); return call.apply(session); });
                health.set(new Health(Status.HEALTHY, "MCP_OK", Instant.now(), 0));
                if (successes != null) successes.increment();
                emit(new Event("MCP_" + operation + "_COMPLETED", identity, capability, "MCP_OK", Instant.now()));
                return result;
            } catch (ToolAdapterException safe) {
                recordFailure(operation, capability, safe.code());
                throw safe;
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                recordFailure(operation, capability, "MCP_CANCELLED");
                throw failure("MCP_CANCELLED");
            } catch (TimeoutException timeout) {
                recordFailure(operation, capability, "MCP_TIMEOUT");
                throw failure("MCP_TIMEOUT");
            } catch (Exception unsafe) {
                if (attempt == attempts) {
                    recordFailure(operation, capability, "MCP_UNAVAILABLE");
                    throw failure("MCP_UNAVAILABLE");
                }
            }
        }
        throw failure("MCP_UNAVAILABLE");
    }

    private <T> T timed(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        Thread worker = Thread.ofVirtual().name("agentworks-mcp-" + identity.connection()).start(future);
        try {
            return future.get(policy.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException();
        } catch (InterruptedException | TimeoutException stopped) {
            future.cancel(true);
            worker.interrupt();
            throw stopped;
        }
    }

    private void recordFailure(String operation, String capability, String code) {
        Status status = code.equals("MCP_SCHEMA_DRIFT") || code.equals("MCP_CAPABILITY_DEGRADED")
                ? Status.DEGRADED : Status.FAILED;
        health.updateAndGet(previous -> new Health(status, code, Instant.now(), previous.failures() + 1));
        if (failures != null) failures.increment();
        emit(new Event("MCP_" + operation + "_FAILED", identity, capability, code, Instant.now()));
    }

    /** Observation is best effort and must never alter an operation's authoritative outcome. */
    private void emit(Event event) {
        try { events.accept(event); } catch (RuntimeException ignored) { }
    }

    private void requireAllowed(Action action, Kind kind, String name, Set<String> allowlist) {
        if (name == null || !allowlist.contains(name) || !access.allow(identity, action, kind, name)) {
            throw failure("MCP_POLICY_DENIED");
        }
    }

    private Map<String, Object> parseArguments(String input) {
        if (input == null || input.getBytes(StandardCharsets.UTF_8).length > policy.maxRequestBytes()) {
            throw failure("MCP_INPUT_INVALID");
        }
        try {
            JsonNode node = JSON.readTree(input);
            if (!node.isObject()) throw failure("MCP_INPUT_INVALID");
            @SuppressWarnings("unchecked") Map<String, Object> value = JSON.convertValue(node, Map.class);
            return Map.copyOf(value);
        } catch (JacksonException | IllegalArgumentException invalid) {
            if (invalid instanceof ToolAdapterException safe) throw safe;
            throw failure("MCP_INPUT_INVALID");
        }
    }

    private Map<String, Object> boundedArguments(Map<String, Object> arguments) {
        try {
            String encoded = JSON.writeValueAsString(arguments == null ? Map.of() : arguments);
            return parseArguments(encoded);
        } catch (JacksonException invalid) { throw failure("MCP_INPUT_INVALID"); }
    }

    private String bounded(String output) {
        if (output == null || output.getBytes(StandardCharsets.UTF_8).length > policy.maxResponseBytes()) {
            throw failure("MCP_OUTPUT_LIMIT");
        }
        return output;
    }

    private static String canonicalSchema(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) throw failure("MCP_SCHEMA_INVALID");
        try {
            return JSON.writeValueAsString(canonicalValue(schema, 0));
        } catch (JacksonException invalid) { throw failure("MCP_SCHEMA_INVALID"); }
    }

    private static Object canonicalValue(Object value, int depth) {
        if (depth > 64) throw failure("MCP_SCHEMA_INVALID");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> {
                if (!(key instanceof String name)) throw failure("MCP_SCHEMA_INVALID");
                sorted.put(name, canonicalValue(nested, depth + 1));
            });
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> canonicalValue(item, depth + 1)).toList();
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw failure("MCP_SCHEMA_INVALID");
    }

    private static String description(String value) {
        if (value == null || value.isBlank()) return "MCP capability";
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    private static ToolAdapterException failure(String code) { return new ToolAdapterException(code); }

    public record Identity(String connection, String namespace, String version, UUID organizationId, UUID workspaceId,
                           UUID agentVersionId, UUID releaseId, UUID deploymentRevisionId, UUID cohortId) {
        public Identity {
            if (connection == null || !NAME.matcher(connection).matches()) throw new IllegalArgumentException("connection");
            if (namespace == null || !NAME.matcher(namespace).matches()) throw new IllegalArgumentException("namespace");
            if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")) throw new IllegalArgumentException("version");
            Objects.requireNonNull(organizationId); Objects.requireNonNull(workspaceId); Objects.requireNonNull(agentVersionId);
            Objects.requireNonNull(releaseId); Objects.requireNonNull(deploymentRevisionId); Objects.requireNonNull(cohortId);
        }
    }

    public record Policy(Set<String> discoverTools, Set<String> invokeTools, Set<String> discoverResources,
                         Set<String> invokeResources, Set<String> discoverPrompts, Set<String> invokePrompts,
                         Map<String, ToolId> toolIdentities, Map<String, ToolEffect> toolEffects,
                         Optional<String> credentialName, Duration timeout,
                         int maxRequestBytes, int maxResponseBytes, int discoveryAttempts, DriftPolicy driftPolicy) {
        public Policy {
            discoverTools = Set.copyOf(discoverTools); invokeTools = Set.copyOf(invokeTools);
            discoverResources = Set.copyOf(discoverResources); invokeResources = Set.copyOf(invokeResources);
            discoverPrompts = Set.copyOf(discoverPrompts); invokePrompts = Set.copyOf(invokePrompts);
            toolIdentities = Map.copyOf(toolIdentities); toolEffects = Map.copyOf(toolEffects);
            credentialName = Objects.requireNonNull(credentialName);
            if (credentialName.isPresent() && !credentialName.get().matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) throw new IllegalArgumentException("credentialName");
            if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0) throw new IllegalArgumentException("timeout");
            if (maxRequestBytes < 1 || maxRequestBytes > 256 * 1024 || maxResponseBytes < 1 || maxResponseBytes > 1024 * 1024) throw new IllegalArgumentException("bounds");
            if (discoveryAttempts < 1 || discoveryAttempts > 2) throw new IllegalArgumentException("discoveryAttempts");
            Objects.requireNonNull(driftPolicy);
            if (!discoverTools.containsAll(invokeTools) || !discoverResources.containsAll(invokeResources)
                    || !discoverPrompts.containsAll(invokePrompts) || !toolEffects.keySet().containsAll(discoverTools)
                    || !toolIdentities.keySet().containsAll(discoverTools)
                    || new HashSet<>(toolIdentities.values()).size() != toolIdentities.size()) {
                throw new IllegalArgumentException("invocation and effects must be a subset of discovery policy");
            }
        }
    }

    public enum DriftPolicy { FAIL_CLOSED, DEGRADE }
    public enum Action { DISCOVER, INVOKE }
    public enum Kind { TOOL, RESOURCE, PROMPT }
    public enum Status { NEW, HEALTHY, DEGRADED, FAILED, CLOSED }
    public record Health(Status status, String code, Instant observedAt, long failures) { }
    public record Event(String type, Identity identity, String capability, String code, Instant occurredAt) { }
    public record Catalog(List<RegisteredMcpTool> tools, List<Resource> resources, List<Prompt> prompts) {
        public Catalog { tools = List.copyOf(tools); resources = List.copyOf(resources); prompts = List.copyOf(prompts); }
    }
    public record Discovery(List<RemoteTool> tools, List<Resource> resources, List<Prompt> prompts) {
        public Discovery { tools = List.copyOf(tools); resources = List.copyOf(resources); prompts = List.copyOf(prompts); }
    }
    public record RemoteTool(String name, String description, Map<String, Object> inputSchema) {
        public RemoteTool { inputSchema = Map.copyOf(inputSchema); }
    }
    public record Resource(String uri, String name, String description, String mimeType, Long size) { }
    public record Prompt(String name, String description) { }

    @FunctionalInterface public interface AccessPolicy { boolean allow(Identity identity, Action action, Kind kind, String name); }
    @FunctionalInterface public interface EventSink { void accept(Event event); }
    @FunctionalInterface public interface SessionFactory { Session open(Identity identity, char[] credentialMaterial) throws Exception; }
    public interface Session extends AutoCloseable {
        void initialize();
        Discovery discover();
        String callTool(String name, Map<String, Object> arguments);
        String readResource(String uri);
        String getPrompt(String name, Map<String, Object> arguments);
        @Override void close();
    }
    @FunctionalInterface private interface SessionCall<T> { T apply(Session session); }
}
