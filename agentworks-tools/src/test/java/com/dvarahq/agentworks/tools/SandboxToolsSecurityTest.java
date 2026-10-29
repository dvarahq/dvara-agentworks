package com.dvarahq.agentworks.tools;

import com.dvarahq.agentworks.agent.springai.*;
import com.dvarahq.agentworks.capability.*;
import com.dvarahq.agentworks.sandbox.*;
import com.dvarahq.agentworks.sandbox.docker.DockerSandboxProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static com.dvarahq.agentworks.tools.SandboxToolsTest.*;

/** Real hostile-input probes run inside digest-pinned Docker, never through host execution. */
class SandboxToolsSecurityTest {
    @TempDir Path temporary;
    @Test void filesystemRoundTripPatchAndEscapeProbesUseOnlyTheRunWorkspace() throws Exception {
        try (var provider = new DockerSandboxProvider(temporary.resolve("fs"), configuration())) {
            AtomicReference<SandboxInstance> allocated = new AtomicReference<>();
            var tools = new SandboxTools(request -> { var sandbox = provider.provision(request); allocated.set(sandbox); return sandbox; });
            try (var scope = new ToolRunScope(context())) {
                var callback = bind(tools.filesystem(ID, "fs", ToolEffect.unclassified()), scope);
                String digest = AdapterSupport.input(fs(callback, "write", "data", "hello", null)).path("digest").asString();
                assertThat(fs(callback, "read", "data", null, null)).contains("aGVsbG8=");
                assertThat(fs(callback, "list", ".", null, null)).contains("data");
                assertThat(fs(callback, "stat", ".", null, null)).contains("directory");
                assertThatThrownBy(() -> fs(callback, "patch", "data", "wrong", "sha256:" + "0".repeat(64)))
                        .hasMessage("FILESYSTEM_PRECONDITION_FAILED");
                fs(callback, "patch", "data", "changed", digest);
                assertThat(fs(callback, "read", "data", null, null)).contains("Y2hhbmdlZA==");
                SandboxInstance sandbox = allocated.get();
                sandbox.execute(List.of("/bin/ln", "-s", "/etc/passwd", "/workspace/link"), Duration.ofSeconds(5));
                sandbox.execute(List.of("/bin/ln", "-s", "/etc", "/workspace/dirlink"), Duration.ofSeconds(5));
                sandbox.execute(List.of("/bin/ln", "/workspace/data", "/workspace/hardlink"), Duration.ofSeconds(5));
                sandbox.execute(List.of("/usr/bin/mkfifo", "/workspace/fifo"), Duration.ofSeconds(5));
                for (String path : List.of("link", "dirlink/passwd", "hardlink", "fifo", "../etc/passwd")) {
                    assertThatThrownBy(() -> fs(callback, "read", path, null, null)).isInstanceOf(ToolAdapterException.class)
                            .hasMessageNotContaining("root:");
                    assertThatThrownBy(() -> fs(callback, "write", path, "denied", null)).isInstanceOf(ToolAdapterException.class);
                }
                fs(callback, "write", "large", "z".repeat(65536), null);
                String large = fs(callback, "read", "large", null, null);
                assertThat(((ArtifactToolCallback) callback).requiresArtifact(large)).isTrue();
                assertThat(AdapterSupport.input(large).path("bytes").asInt()).isEqualTo(65536);
            }
            assertThat(provider.activeSandboxCount()).isZero();
            try (var other = new ToolRunScope(context())) {
                var callback = bind(tools.filesystem(ID, "fs", ToolEffect.unclassified()), other);
                assertThatThrownBy(() -> fs(callback, "read", "data", null, null)).hasMessage("FILESYSTEM_OPERATION_FAILED");
            }
            assertThat(provider.activeSandboxCount()).isZero();
        }
    }

    @Test void shellUsesLiteralArgumentsEmptyEnvironmentAndBoundedBinaryArtifacts() throws Exception {
        try (var provider = new DockerSandboxProvider(temporary.resolve("shell"), configuration());
             var scope = new ToolRunScope(context())) {
            var tools = new SandboxTools(provider::provision);
            var echo = bind(tools.shell(ID, "echo", new SandboxTools.CommandPolicy("/bin/echo", List.of(".*"), Map.of(), Duration.ofSeconds(5)), ToolEffect.unclassified()), scope);
            String literal = "$(id); touch /workspace/escaped";
            var result = AdapterSupport.input(echo.call(AdapterSupport.JSON.writeValueAsString(Map.of("arguments", List.of(literal)))));
            assertThat(new String(Base64.getDecoder().decode(result.path("body").asString()), StandardCharsets.UTF_8)).isEqualTo(literal + "\n");
            assertThat(((ArtifactToolCallback) echo).requiresArtifact(result.toString())).isTrue();
            var env = bind(tools.shell(ID, "env", new SandboxTools.CommandPolicy("/usr/bin/env", List.of(), Map.of("LABEL", "safe"), Duration.ofSeconds(5)), ToolEffect.unclassified()), scope);
            var output = AdapterSupport.input(env.call("{\"arguments\":[],\"environment\":{\"LABEL\":\"safe\"}}"));
            assertThat(new String(Base64.getDecoder().decode(output.path("body").asString()), StandardCharsets.UTF_8)).isEqualTo("LABEL=safe\n");
            var python = bind(tools.shell(ID, "python", new SandboxTools.CommandPolicy("/usr/local/bin/python3", List.of("-c", ".*"), Map.of(), Duration.ofSeconds(5)), ToolEffect.unclassified()), scope);
            assertThatThrownBy(() -> python.call("{\"arguments\":[\"-c\",\"print('x'*70000)\"]}")).hasMessage("TOOL_OUTPUT_LIMIT");
            var fs = bind(tools.filesystem(ID, "fs", ToolEffect.unclassified()), scope);
            assertThatThrownBy(() -> fs(fs, "stat", "escaped", null, null)).hasMessage("FILESYSTEM_OPERATION_FAILED");
        }
    }

    @Test void timeoutAndInterruptionRetireTheWholeSandbox() throws Exception {
        try (var provider = new DockerSandboxProvider(temporary.resolve("timeout"), configuration())) {
            var tools = new SandboxTools(provider::provision);
            try (var scope = new ToolRunScope(context())) {
                var sleep = bind(tools.shell(ID, "sleep", new SandboxTools.CommandPolicy("/bin/sleep", List.of("30"), Map.of(), Duration.ofMillis(200)), ToolEffect.unclassified()), scope);
                assertThatThrownBy(() -> sleep.call("{\"arguments\":[\"30\"]}")).isInstanceOf(ToolAdapterException.class);
            }
            assertThat(provider.activeSandboxCount()).isZero();
            try (var scope = new ToolRunScope(context())) {
                var sleep = bind(tools.shell(ID, "sleep", new SandboxTools.CommandPolicy("/bin/sleep", List.of("30"), Map.of(), Duration.ofSeconds(60)), ToolEffect.unclassified()), scope);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread caller = Thread.ofVirtual().start(() -> { try { sleep.call("{\"arguments\":[\"30\"]}"); } catch (Throwable error) { failure.set(error); } });
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (provider.liveExecutionCount() == 0 && caller.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(provider.liveExecutionCount()).isEqualTo(1);
                caller.interrupt(); caller.join(Duration.ofSeconds(10));
                assertThat(caller.isAlive()).isFalse();
                assertThat(failure.get()).hasMessage("TOOL_CANCELLED");
            }
            assertThat(provider.activeSandboxCount()).isZero();
        }
    }

    static String fs(ToolCallback callback, String operation, String path, String content, String digest) {
        Map<String, Object> request = new LinkedHashMap<>(Map.of("operation", operation, "path", path));
        if (content != null) request.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        if (digest != null) request.put("expectedDigest", digest);
        return callback.call(AdapterSupport.JSON.writeValueAsString(request));
    }
}
