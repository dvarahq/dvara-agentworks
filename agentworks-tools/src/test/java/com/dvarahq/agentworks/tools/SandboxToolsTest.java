package com.dvarahq.agentworks.tools;

import com.dvarahq.agentworks.agent.springai.*;
import com.dvarahq.agentworks.capability.*;
import com.dvarahq.agentworks.domain.run.*;
import com.dvarahq.agentworks.domain.sandbox.*;
import com.dvarahq.agentworks.runplane.execution.*;
import com.dvarahq.agentworks.sandbox.SandboxInstance;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SandboxToolsTest {
    static final ToolId ID = new ToolId("test", "sandbox", "1.0.0");
    static final String IMAGE = "python@sha256:4c47124a8391cb7a9f571164147d154777cf012a4ece5f86097130d7a4478111";
    static SandboxConfiguration configuration() {
        return new SandboxConfiguration("docker", "demand", Optional.of(IMAGE), new BigDecimal("0.5"), "64Mi", 32, "NONE", "2m");
    }
    static RunContext context() {
        RunContext run = mock(RunContext.class);
        RunPins pins = mock(RunPins.class);
        when(pins.id()).thenReturn(UUID.randomUUID()); when(pins.workspaceId()).thenReturn(UUID.randomUUID());
        when(pins.releaseId()).thenReturn(UUID.randomUUID()); when(pins.revisionId()).thenReturn(UUID.randomUUID());
        when(pins.cohortId()).thenReturn(UUID.randomUUID()); when(run.pins()).thenReturn(pins);
        var invoker = new RunInvoker(UUID.randomUUID(), pins.workspaceId(), UUID.randomUUID(), UUID.randomUUID(), "api-token");
        when(run.invoker()).thenReturn(Optional.of(invoker));
        var manifest = mock(ReleaseManifestReader.RunManifest.class);
        when(manifest.sandboxConfiguration()).thenReturn(Optional.of(configuration())); when(run.manifest()).thenReturn(manifest);
        when(run.checkpointState()).thenReturn(Optional.empty());
        return run;
    }
    static ToolCallback bind(RegisteredTool tool, ToolRunScope scope) {
        return ((RunBoundToolCallback) tool.binding().callback()).bind(scope);
    }

    @Test void resourcesAreLazyOwnedAndSharedOnlyWithinOneRunScope() throws Exception {
        AtomicInteger created = new AtomicInteger(), closed = new AtomicInteger();
        var tools = new SandboxTools(request -> {
            created.incrementAndGet();
            var sandbox = mock(SandboxInstance.class);
            when(sandbox.reference()).thenReturn(new SandboxRef(UUID.randomUUID(), request.owner()));
            try { when(sandbox.executeRaw(anyList(), any())).thenReturn("{\"result\":{\"type\":\"file\",\"bytes\":1}}"); }
            catch (InterruptedException impossible) { throw new AssertionError(impossible); }
            doAnswer(call -> { closed.incrementAndGet(); return null; }).when(sandbox).close();
            return sandbox;
        });
        var registered = tools.filesystem(ID, "fs", ToolEffect.unclassified());
        assertThatThrownBy(() -> registered.binding().callback().call("{}")).hasMessage("TOOL_RUN_BINDING_REQUIRED");
        for (int index = 1; index <= 2; index++) {
            try (var scope = new ToolRunScope(context())) {
                ToolCallback callback = bind(registered, scope);
                assertThat(((ArtifactToolCallback) callback).requiresArtifact("{\n  \"encoding\": \"base64\", \"body\": \"AAH/\"\n}")).isTrue();
                assertThat(((ArtifactToolCallback) callback).requiresArtifact("redacted result")).isTrue();
                assertThat(created).hasValue(index - 1);
                callback.call("{\"operation\":\"stat\",\"path\":\"x\"}");
                callback.call("{\"operation\":\"stat\",\"path\":\"x\"}");
                assertThat(created).hasValue(index);
            }
            assertThat(closed).hasValue(index);
        }
    }

    @Test void pathAndInputValidationNeverProvisionsASandbox() throws Exception {
        var tools = new SandboxTools(request -> { throw new AssertionError("must not provision"); });
        try (var scope = new ToolRunScope(context())) {
            var callback = bind(tools.filesystem(ID, "fs", ToolEffect.unclassified()), scope);
            for (String path : List.of("../x", "/etc/passwd", "a/../../x", "a//b", "a/./b", "a\\b", ".")) {
                assertThatThrownBy(() -> callback.call(AdapterSupport.JSON.writeValueAsString(Map.of("operation", "read", "path", path))))
                        .hasMessage("FILESYSTEM_PATH_DENIED");
            }
            assertThatThrownBy(() -> callback.call("{\"operation\":\"write\",\"path\":\"x\",\"content\":\"!!!\"}"))
                    .hasMessage("TOOL_INPUT_INVALID");
        }
    }

    @Test void restoreLocalProviderAndWrongWorkspaceFailClosed() throws Exception {
        var tools = new SandboxTools(request -> { throw new AssertionError("must not provision"); });
        for (String scenario : List.of("recovered", "checkpoint", "local", "workspace")) {
            RunContext run = context();
            String expected;
            switch (scenario) {
                case "recovered" -> { when(run.recovered()).thenReturn(true); expected = "SANDBOX_RESTORE_UNAVAILABLE"; }
                case "checkpoint" -> { when(run.checkpointState()).thenReturn(Optional.of(AdapterSupport.input("{}"))); expected = "SANDBOX_RESTORE_UNAVAILABLE"; }
                case "local" -> {
                    var local = new SandboxConfiguration("local", "demand", Optional.empty(), BigDecimal.ONE, "64Mi", 32, "NONE", "2m");
                    when(run.manifest().sandboxConfiguration()).thenReturn(Optional.of(local)); expected = "SANDBOX_ISOLATION_REQUIRED";
                }
                default -> {
                    when(run.invoker()).thenReturn(Optional.of(new RunInvoker(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "api-token")));
                    expected = "SANDBOX_OWNER_MISMATCH";
                }
            }
            try (var scope = new ToolRunScope(run)) {
                var callback = bind(tools.filesystem(ID, "fs", ToolEffect.unclassified()), scope);
                assertThatThrownBy(() -> callback.call("{\"operation\":\"list\",\"path\":\".\"}")).hasMessage(expected);
            }
        }
    }

    @Test void foreignResourceIsNeitherUsedNorDeleted() throws Exception {
        var foreign = mock(SandboxInstance.class);
        when(foreign.reference()).thenReturn(new SandboxRef(UUID.randomUUID(), new SandboxOwner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())));
        var tools = new SandboxTools(request -> foreign);
        try (var scope = new ToolRunScope(context())) {
            var callback = bind(tools.filesystem(ID, "fs", ToolEffect.unclassified()), scope);
            assertThatThrownBy(() -> callback.call("{\"operation\":\"stat\",\"path\":\"x\"}")).hasMessage("SANDBOX_OWNER_MISMATCH");
        }
        verify(foreign, never()).executeRaw(anyList(), any()); verify(foreign, never()).close();
    }

    @Test void shellAllowsOnlyFixedArgumentPositionsAndEnvironment() throws Exception {
        for (String expression : List.of("(a+)+", "a*a*", "(?=a)a", "(a)\\1", "a|b", "x".repeat(257))) {
            assertThatIllegalArgumentException().isThrownBy(() -> new SandboxTools.CommandPolicy("/bin/echo", List.of(expression), Map.of(), Duration.ofSeconds(1)));
        }
        for (String key : List.of("PATH", "LD_PRELOAD", "DYLD_INSERT_LIBRARIES", "BASH_ENV", "PYTHONPATH", "JAVA_TOOL_OPTIONS")) {
            assertThatIllegalArgumentException().isThrownBy(() -> new SandboxTools.CommandPolicy("/bin/echo", List.of(".*"), Map.of(key, ".*"), Duration.ofSeconds(1)));
        }
        var tools = new SandboxTools(request -> { throw new AssertionError("must not provision"); });
        var policy = new SandboxTools.CommandPolicy("/bin/echo", List.of("[a-z]+"), Map.of("LABEL", "[a-z]+"), Duration.ofSeconds(1));
        try (var scope = new ToolRunScope(context())) {
            var callback = bind(tools.shell(ID, "echo", policy, ToolEffect.unclassified()), scope);
            assertThat(((ArtifactToolCallback) callback).requiresArtifact("{\"encoding\": \"base64\", \"stdout\": \"AAH/\"}")).isTrue();
            for (String input : List.of("{\"arguments\":[\"hello; id\"]}", "{\"arguments\":[\"ok\",\"extra\"]}",
                    "{\"arguments\":[\"ok\"],\"environment\":{\"PATH\":\"bad\"}}")) {
                assertThatThrownBy(() -> callback.call(input)).isInstanceOf(ToolAdapterException.class);
            }
        }
    }
}
