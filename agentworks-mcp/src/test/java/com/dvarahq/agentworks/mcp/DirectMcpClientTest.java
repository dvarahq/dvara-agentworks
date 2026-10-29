package com.dvarahq.agentworks.mcp;

import com.dvarahq.agentworks.agent.springai.ArtifactToolCallback;
import com.dvarahq.agentworks.agent.springai.CredentialedToolCallback;
import com.dvarahq.agentworks.agent.springai.ToolAdapterException;
import com.dvarahq.agentworks.capability.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;

class DirectMcpClientTest {
    static final DirectMcpClient.Identity IDENTITY = new DirectMcpClient.Identity("support", "support", "1.0.0",
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    static final Map<String, Object> SCHEMA = Map.of("type", "object", "additionalProperties", false,
            "properties", Map.of("value", Map.of("type", "integer")), "required", List.of("value"));

    @Test void discoveryAndInvocationUseSeparatePolicyStableIdentityAndArtifacts() {
        var opened = new AtomicInteger(); var closed = new AtomicInteger(); var events = new ArrayList<DirectMcpClient.Event>();
        var meters = new SimpleMeterRegistry();
        var client = new DirectMcpClient(IDENTITY, policy(Optional.empty()),
                (identity, action, kind, name) -> !(kind == DirectMcpClient.Kind.PROMPT && action == DirectMcpClient.Action.DISCOVER),
                (identity, credential) -> { opened.incrementAndGet(); return session(closed, SCHEMA); }, events::add, meters);

        DirectMcpClient.Catalog catalog = client.discover(null);
        assertThat(catalog.tools()).singleElement().satisfies(tool -> {
            assertThat(tool.metadata().id()).isEqualTo(new ToolId("support", "add", "1.0.0"));
            assertThat(tool.metadata().type()).isEqualTo(ToolType.MCP);
            assertThat(tool.connection()).isEqualTo(IDENTITY);
            assertThat(((ArtifactToolCallback) tool.binding().callback()).requiresArtifact("small")).isTrue();
            assertThat(tool.binding().callback().call("{\"value\":2}")).contains("result", "3");
        });
        assertThat(catalog.resources()).extracting(DirectMcpClient.Resource::uri).containsExactly("support://guide");
        assertThat(catalog.prompts()).isEmpty();
        assertThat(opened).hasValue(2); assertThat(closed).hasValue(2);
        assertThat(client.health().status()).isEqualTo(DirectMcpClient.Status.HEALTHY);
        assertThat(events).extracting(DirectMcpClient.Event::type).contains("MCP_DISCOVERED", "MCP_CALL_TOOL_COMPLETED");
        assertThat(meters.get("agentworks.mcp.operations").tag("outcome", "success").counter().count()).isEqualTo(2);
    }

    @Test void credentialIsJitAndRotationDoesNotChangePinnedIdentity() {
        var materials = new ArrayList<String>();
        var client = new DirectMcpClient(IDENTITY, policy(Optional.of("mcp.support")), allowAll(),
                (identity, material) -> { materials.add(material == null ? "none" : new String(material)); return session(new AtomicInteger(), SCHEMA); },
                null, null);
        RegisteredMcpTool tool = client.discover("first-secret".toCharArray()).tools().getFirst();
        CredentialedToolCallback callback = (CredentialedToolCallback) tool.binding().callback();
        callback.call("{\"value\":4}", null, "rotated-secret".toCharArray());
        assertThat(materials).containsExactly("first-secret", "rotated-secret");
        assertThat(tool.toString()).doesNotContain("first-secret", "rotated-secret");
    }

    @Test void schemaDriftFailsOrDegradesWithoutSilentSubstitution() {
        var current = new AtomicReference<>(SCHEMA);
        for (DirectMcpClient.DriftPolicy drift : DirectMcpClient.DriftPolicy.values()) {
            var client = new DirectMcpClient(IDENTITY, policy(Optional.empty(), drift), allowAll(),
                    (identity, material) -> session(new AtomicInteger(), current.get()), null, null);
            RegisteredMcpTool pinned = client.discover(null).tools().getFirst();
            current.set(Map.of("type", "object", "additionalProperties", false,
                    "properties", Map.of("changed", Map.of("type", "string"))));
            assertThatThrownBy(() -> pinned.binding().callback().call("{\"value\":1}"))
                    .isInstanceOf(ToolAdapterException.class)
                    .hasMessage(drift == DirectMcpClient.DriftPolicy.FAIL_CLOSED ? "MCP_SCHEMA_DRIFT" : "MCP_CAPABILITY_DEGRADED")
                    .hasNoCause();
            assertThat(client.health().status()).isEqualTo(DirectMcpClient.Status.DEGRADED);
            assertThat(client.health().failures()).isEqualTo(1);
            current.set(SCHEMA);
        }
    }

    @Test void timeoutCancellationRetryAndFailuresAreBoundedAndSecretFree() throws Exception {
        AtomicInteger attempts = new AtomicInteger(), closes = new AtomicInteger();
        var events = new ArrayList<DirectMcpClient.Event>();
        var retrying = new DirectMcpClient(IDENTITY, policy(Optional.empty()), allowAll(), (identity, material) -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("secret-canary");
            return session(closes, SCHEMA);
        }, events::add, null);
        assertThat(retrying.discover(null).tools()).hasSize(1);
        assertThat(attempts).hasValue(2);

        var slow = new DirectMcpClient(IDENTITY, policy(Optional.empty(), Duration.ofMillis(30)), allowAll(),
                (identity, material) -> new StubSession() {
                    @Override public DirectMcpClient.Discovery discover() {
                        try { Thread.sleep(10_000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                        return discovery(SCHEMA);
                    }
                }, events::add, null);
        assertThatThrownBy(() -> slow.discover(null)).hasMessage("MCP_TIMEOUT").hasNoCause();
        assertThat(events.toString()).doesNotContain("secret-canary");

        var entered = new CountDownLatch(1);
        var cancelling = new DirectMcpClient(IDENTITY, policy(Optional.empty(), Duration.ofSeconds(2)), allowAll(),
                (identity, material) -> new StubSession() {
                    @Override public DirectMcpClient.Discovery discover() {
                        entered.countDown();
                        try { Thread.sleep(10_000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                        return discovery(SCHEMA);
                    }
                }, events::add, null);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> { try { cancelling.discover(null); } catch (Throwable stopped) { failure.set(stopped); } });
        entered.await(); caller.interrupt(); caller.join();
        assertThat(failure.get()).isInstanceOf(ToolAdapterException.class).hasMessage("MCP_CANCELLED");
    }

    @Test void resourcesAndPromptsAreExplicitlyInvocationGatedAndBounded() {
        var client = new DirectMcpClient(IDENTITY, policy(Optional.empty()), allowAll(),
                (identity, material) -> session(new AtomicInteger(), SCHEMA), null, null);
        assertThat(client.readResource("support://guide", null)).contains("guide");
        assertThat(client.getPrompt("summarize", Map.of("topic", "x"), null)).contains("summarize");
        assertThatThrownBy(() -> client.readResource("support://hidden", null)).hasMessage("MCP_POLICY_DENIED");
    }

    private static DirectMcpClient.Policy policy(Optional<String> credential) {
        return policy(credential, DirectMcpClient.DriftPolicy.FAIL_CLOSED);
    }
    private static DirectMcpClient.Policy policy(Optional<String> credential, DirectMcpClient.DriftPolicy drift) {
        return policy(credential, drift, Duration.ofSeconds(1));
    }
    private static DirectMcpClient.Policy policy(Optional<String> credential, Duration timeout) {
        return policy(credential, DirectMcpClient.DriftPolicy.FAIL_CLOSED, timeout);
    }
    private static DirectMcpClient.Policy policy(Optional<String> credential, DirectMcpClient.DriftPolicy drift, Duration timeout) {
        return new DirectMcpClient.Policy(Set.of("add"), Set.of("add"), Set.of("support://guide"),
                Set.of("support://guide"), Set.of("summarize"), Set.of("summarize"),
                Map.of("add", new ToolId("support", "add", "1.0.0")),
                Map.of("add", new ToolEffect("support", "read", com.dvarahq.agentworks.domain.security.PermissionRisk.LOW)),
                credential, timeout, 16 * 1024, 64 * 1024, 2, drift);
    }
    private static DirectMcpClient.AccessPolicy allowAll() { return (identity, action, kind, name) -> true; }

    private static DirectMcpClient.Session session(AtomicInteger closed, Map<String, Object> schema) {
        return new StubSession() {
            @Override public DirectMcpClient.Discovery discover() { return discovery(schema); }
            @Override public String callTool(String name, Map<String, Object> arguments) { return "{\"result\":" + (((Number) arguments.get("value")).intValue() + 1) + "}"; }
            @Override public void close() { closed.incrementAndGet(); }
        };
    }
    private static DirectMcpClient.Discovery discovery(Map<String, Object> schema) {
        return new DirectMcpClient.Discovery(List.of(new DirectMcpClient.RemoteTool("add", "Add one", schema)),
                List.of(new DirectMcpClient.Resource("support://guide", "guide", "Guide", "text/plain", 5L)),
                List.of(new DirectMcpClient.Prompt("summarize", "Summarize")));
    }
    private abstract static class StubSession implements DirectMcpClient.Session {
        @Override public void initialize() { }
        @Override public DirectMcpClient.Discovery discover() { return discovery(SCHEMA); }
        @Override public String callTool(String name, Map<String, Object> arguments) { return "{}"; }
        @Override public String readResource(String uri) { return "{\"text\":\"guide\"}"; }
        @Override public String getPrompt(String name, Map<String, Object> arguments) { return "{\"prompt\":\"summarize\"}"; }
        @Override public void close() { }
    }
}
