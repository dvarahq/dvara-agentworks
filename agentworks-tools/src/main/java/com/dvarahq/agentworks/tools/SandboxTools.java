package com.dvarahq.agentworks.tools;

import com.dvarahq.agentworks.agent.springai.*;
import com.dvarahq.agentworks.capability.*;
import com.dvarahq.agentworks.domain.sandbox.*;
import com.dvarahq.agentworks.sandbox.SandboxCoordinator;
import com.dvarahq.agentworks.sandbox.SandboxInstance;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/** Shared immutable factory; each continuation owns one demand-provisioned, exact-run Docker workspace. */
public final class SandboxTools {
    private static final String FILESYSTEM = helper("filesystem.py");
    private static final String SHELL = helper("shell.py");
    private final Function<SandboxProvisionRequest, SandboxInstance> provision;

    public SandboxTools(SandboxCoordinator coordinator) { this.provision = Objects.requireNonNull(coordinator)::provision; }
    SandboxTools(Function<SandboxProvisionRequest, SandboxInstance> provision) { this.provision = provision; }

    public RegisteredTool filesystem(ToolId id, String name, ToolEffect effect) {
        ToolDefinition definition = AdapterSupport.definition(name, "Bounded operations in the run sandbox workspace",
                "\"operation\":{\"enum\":[\"read\",\"write\",\"list\",\"stat\",\"patch\"]},"
                + "\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1024},"
                + "\"content\":{\"type\":\"string\",\"maxLength\":87384},"
                + "\"expectedDigest\":{\"type\":\"string\",\"minLength\":71,\"maxLength\":71}",
                "\"operation\",\"path\"");
        return RegisteredTool.of(id, ToolType.FILESYSTEM, effect, new Scoped(definition) {
            @Override String invoke(ToolRunScope scope, String input) {
                JsonNode request = AdapterSupport.input(input);
                String operation = AdapterSupport.text(request, "operation"), path = AdapterSupport.text(request, "path");
                if (!Set.of("read", "write", "list", "stat", "patch").contains(operation)
                        || !Set.of("operation", "path", "content", "expectedDigest").containsAll(request.propertyNames())) {
                    throw AdapterSupport.failure("TOOL_INPUT_INVALID");
                }
                boolean root = path.equals(".") && Set.of("list", "stat").contains(operation);
                if (!root && (path.length() > 1024 || path.startsWith("/") || path.contains("\\")
                        || Arrays.stream(path.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals("..")))) {
                    throw AdapterSupport.failure("FILESYSTEM_PATH_DENIED");
                }
                if (operation.equals("write") || operation.equals("patch")) {
                    try {
                        if (Base64.getDecoder().decode(AdapterSupport.text(request, "content")).length > 64 * 1024) {
                            throw AdapterSupport.failure("TOOL_INPUT_INVALID");
                        }
                    } catch (IllegalArgumentException invalid) { throw AdapterSupport.failure("TOOL_INPUT_INVALID"); }
                }
                if (operation.equals("patch") && !AdapterSupport.text(request, "expectedDigest").matches("sha256:[0-9a-f]{64}")) {
                    throw AdapterSupport.failure("TOOL_INPUT_INVALID");
                }
                String result = execute(scope, List.of("/usr/bin/env", "-i", "PATH=/usr/local/bin:/usr/bin:/bin",
                        "/usr/local/bin/python3", "-I", "-S", "-c", FILESYSTEM, request.toString()), Duration.ofSeconds(10));
                JsonNode decoded = AdapterSupport.input(result);
                if (decoded.has("error")) {
                    String code = AdapterSupport.text(decoded, "error");
                    if (!Set.of("FILESYSTEM_PATH_DENIED", "FILESYSTEM_TYPE_DENIED", "FILESYSTEM_PRECONDITION_FAILED",
                            "TOOL_INPUT_INVALID", "TOOL_OUTPUT_LIMIT", "FILESYSTEM_OPERATION_FAILED").contains(code)) {
                        throw AdapterSupport.failure("FILESYSTEM_OPERATION_FAILED");
                    }
                    throw AdapterSupport.failure(code);
                }
                if (!decoded.has("result")) throw AdapterSupport.failure("FILESYSTEM_OPERATION_FAILED");
                return AdapterSupport.bounded(decoded.get("result").toString());
            }
        }, null);
    }

    /** Exact executable and argument positions; environment starts empty and accepts only declared names/values. */
    public record CommandPolicy(String executable, List<String> argumentPatterns, Map<String, String> environmentPatterns,
                                Duration timeout) {
        public CommandPolicy {
            if (executable == null || !executable.matches("/[A-Za-z0-9_./-]{1,255}") || executable.contains("..")) {
                throw new IllegalArgumentException("a fixed absolute sandbox executable is required");
            }
            argumentPatterns = List.copyOf(argumentPatterns); environmentPatterns = Map.copyOf(environmentPatterns);
            if (argumentPatterns.size() > 32 || environmentPatterns.size() > 16) throw new IllegalArgumentException("command bounds");
            argumentPatterns.forEach(AdapterSupport::safePattern);
            environmentPatterns.forEach((key, pattern) -> {
                if (!key.matches("[A-Z][A-Z0-9_]{0,63}") || key.startsWith("LD_") || key.startsWith("DYLD_")
                        || Set.of("PATH", "ENV", "BASH_ENV", "PYTHONPATH", "PYTHONHOME", "JAVA_TOOL_OPTIONS",
                                  "JDK_JAVA_OPTIONS", "NODE_OPTIONS", "SHELLOPTS", "BASHOPTS").contains(key)) {
                    throw new IllegalArgumentException("unsafe environment name");
                }
                AdapterSupport.safePattern(pattern);
            });
            timeout = AdapterSupport.timeout(timeout);
        }
    }

    public RegisteredTool shell(ToolId id, String name, CommandPolicy policy, ToolEffect effect) {
        var schema = AdapterSupport.JSON.createObjectNode().put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("type", "object").put("additionalProperties", false)
                .put("$comment", "Shell adapter 1: " + policy.executable() + " " + policy.timeout());
        var properties = schema.putObject("properties");
        var arguments = properties.putObject("arguments").put("type", "array").put("minItems", policy.argumentPatterns().size())
                .put("maxItems", policy.argumentPatterns().size());
        if (!policy.argumentPatterns().isEmpty()) {
            var positions = arguments.putArray("prefixItems");
            policy.argumentPatterns().forEach(pattern -> positions.addObject().put("type", "string").put("maxLength", 4096)
                    .put("$comment", "adapter policy: " + pattern));
        }
        arguments.put("items", false);
        var env = properties.putObject("environment").put("type", "object").put("additionalProperties", false).putObject("properties");
        policy.environmentPatterns().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> env
                .putObject(entry.getKey()).put("type", "string").put("maxLength", 1024)
                .put("$comment", "adapter policy: " + entry.getValue()));
        schema.putArray("required").add("arguments");
        ToolDefinition definition = ToolDefinition.builder().name(name).description("Execute the registered command in the run sandbox")
                .inputSchema(schema.toString()).build();
        return RegisteredTool.of(id, ToolType.SHELL, effect, new Scoped(definition) {
            @Override String invoke(ToolRunScope scope, String input) {
                JsonNode request = AdapterSupport.input(input), args = request.path("arguments");
                if (!args.isArray() || args.size() != policy.argumentPatterns().size()
                        || !Set.of("arguments", "environment").containsAll(request.propertyNames())) {
                    throw AdapterSupport.failure("SHELL_ARGUMENT_DENIED");
                }
                List<String> command = new ArrayList<>();
                Map<String, String> environmentValues = new TreeMap<>();
                if (request.has("environment")) {
                    JsonNode environment = request.get("environment");
                    if (!environment.isObject()) throw AdapterSupport.failure("SHELL_ENVIRONMENT_DENIED");
                    for (String key : new TreeSet<>(environment.propertyNames())) {
                        String value = AdapterSupport.text(environment, key), pattern = policy.environmentPatterns().get(key);
                        if (pattern == null || value.length() > 1024 || !value.matches(pattern)) {
                            throw AdapterSupport.failure("SHELL_ENVIRONMENT_DENIED");
                        }
                        environmentValues.put(key, value);
                    }
                }
                command.add(policy.executable());
                for (int index = 0; index < args.size(); index++) {
                    JsonNode value = args.get(index);
                    if (!value.isString() || value.stringValue().length() > 4096 || value.stringValue().indexOf('\0') >= 0
                            || !value.stringValue().matches(policy.argumentPatterns().get(index))) {
                        throw AdapterSupport.failure("SHELL_ARGUMENT_DENIED");
                    }
                    command.add(value.stringValue());
                }
                String protocol = AdapterSupport.JSON.writeValueAsString(Map.of("command", command,
                        "environment", environmentValues, "timeout", policy.timeout().toMillis()));
                // Keep each OS argument below Linux MAX_ARG_STRLEN; reject oversized combined structured input.
                if (protocol.getBytes(StandardCharsets.UTF_8).length > 96 * 1024) throw AdapterSupport.failure("TOOL_INPUT_INVALID");
                String result = execute(scope, List.of("/usr/bin/env", "-i", "PATH=/usr/local/bin:/usr/bin:/bin",
                        "/usr/local/bin/python3", "-I", "-S", "-c", SHELL, protocol), policy.timeout());
                JsonNode decoded = AdapterSupport.input(result);
                if (decoded.has("error")) {
                    String code = AdapterSupport.text(decoded, "error");
                    if (!Set.of("TOOL_OUTPUT_LIMIT", "SHELL_TIMEOUT", "SHELL_EXECUTION_FAILED").contains(code)) {
                        throw AdapterSupport.failure("SHELL_EXECUTION_FAILED");
                    }
                    throw AdapterSupport.failure(code);
                }
                if (!decoded.has("result")) throw AdapterSupport.failure("SHELL_EXECUTION_FAILED");
                return AdapterSupport.bounded(decoded.get("result").toString());
            }
        }, null);
    }

    private String execute(ToolRunScope scope, List<String> command, Duration timeout) {
        SandboxInstance sandbox = scope.resource(this, () -> acquire(scope));
        synchronized (sandbox) {
            try { return AdapterSupport.bounded(sandbox.executeRaw(command, timeout)); }
            catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt(); throw AdapterSupport.failure("TOOL_CANCELLED");
            } catch (ToolAdapterException refusal) { throw refusal; }
            catch (RuntimeException failure) { throw AdapterSupport.failure("SANDBOX_TOOL_FAILED"); }
        }
    }

    private SandboxInstance acquire(ToolRunScope scope) {
        var run = scope.context();
        if (run.recovered() || run.checkpointState().isPresent()) throw AdapterSupport.failure("SANDBOX_RESTORE_UNAVAILABLE");
        var pins = run.pins();
        var invoker = run.invoker().orElseThrow(() -> AdapterSupport.failure("INVOKER_REQUIRED"));
        if (!pins.workspaceId().equals(invoker.workspaceId())) throw AdapterSupport.failure("SANDBOX_OWNER_MISMATCH");
        var configuration = run.manifest().sandboxConfiguration()
                .orElseThrow(() -> AdapterSupport.failure("SANDBOX_CONFIGURATION_REQUIRED"));
        if (!configuration.provider().equals("docker")) throw AdapterSupport.failure("SANDBOX_ISOLATION_REQUIRED");
        var owner = new SandboxOwner(invoker.organizationId(), pins.workspaceId(), pins.id(), pins.releaseId(),
                pins.revisionId(), pins.cohortId());
        var instance = provision.apply(new SandboxProvisionRequest(owner, Optional.of(configuration), Set.of(
                SandboxCapability.PROCESS_EXECUTION, SandboxCapability.HOSTILE_CODE_ISOLATION,
                SandboxCapability.DIGEST_PINNED_IMAGE, SandboxCapability.NON_ROOT_IDENTITY,
                SandboxCapability.CPU_LIMIT, SandboxCapability.MEMORY_LIMIT, SandboxCapability.PID_LIMIT,
                SandboxCapability.NETWORK_EGRESS_CONTROL)));
        if (!instance.reference().owner().equals(owner)) {
            // Never operate on or delete another run's resource returned by a broken provider.
            throw AdapterSupport.failure("SANDBOX_OWNER_MISMATCH");
        }
        return instance;
    }

    private abstract static class Scoped implements RunBoundToolCallback {
        private final ToolDefinition definition;
        Scoped(ToolDefinition definition) { this.definition = definition; }
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String input) { throw AdapterSupport.failure("TOOL_RUN_BINDING_REQUIRED"); }
        @Override public ToolCallback bind(ToolRunScope scope) {
            return new ArtifactToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return definition; }
                @Override public String call(String input) { return invoke(scope, input); }
                @Override public String call(String input, ToolContext context) { return call(input); }
                @Override public boolean requiresArtifact(String output) {
                    // Guardrails can redact/reformat envelopes. Never infer binary safety from output text.
                    return true;
                }
            };
        }
        abstract String invoke(ToolRunScope scope, String input);
    }

    private static String helper(String name) {
        try (var source = SandboxTools.class.getResourceAsStream(name)) {
            if (source == null) throw new IllegalStateException("filesystem helper missing");
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("filesystem helper unavailable"); }
    }
}
