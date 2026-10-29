package com.dvarahq.agentworks.a2a;

import com.dvarahq.agentworks.domain.external.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class A2aExposureAdapterTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final String SECRET = "inbound-test-secret";
    private final FakeExecutor executor = new FakeExecutor();
    private final A2aExposureAdapter adapter = new A2aExposureAdapter(executor, new ExternalInteractionHandler() {
        @Override public String invokeTool(String request) { return "tool:" + request; }
        @Override public String transferArtifact(String reference) { return "artifact:" + reference; }
    }, key -> key.equals("caller") ? SECRET.toCharArray() : null,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(2), 4096, 8);

    @Test
    void authenticatesAndRoutesVersionedA2aOperations() throws Exception {
        A2aOutboundResponse response = adapter.handle(signed(A2aExposureAdapter.HANDSHAKE_PATH,
                JSON.writeValueAsBytes(requirements()), "nonce-1", NOW));

        assertThat(response.status()).isEqualTo(200);
        assertThat(executor.negotiations).hasValue(1);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("fake-a2a");
    }

    @Test
    void rejectsReplayTamperingAndExpiredAuthenticationBeforeDispatch() throws Exception {
        byte[] body = JSON.writeValueAsBytes(requirements());
        A2aInboundRequest valid = signed(A2aExposureAdapter.HANDSHAKE_PATH, body, "nonce-1", NOW);
        assertThat(adapter.handle(valid).status()).isEqualTo(200);
        assertThat(adapter.handle(valid).status()).isEqualTo(401);

        A2aInboundRequest signed = signed(A2aExposureAdapter.HANDSHAKE_PATH, body, "nonce-2", NOW);
        assertThat(adapter.handle(new A2aInboundRequest(signed.method(), signed.path(), signed.headers(),
                "tampered".getBytes(StandardCharsets.UTF_8))).status()).isEqualTo(401);
        assertThat(adapter.handle(signed(A2aExposureAdapter.HANDSHAKE_PATH, body, "nonce-3",
                NOW.minus(Duration.ofMinutes(3)))).status()).isEqualTo(401);
        assertThat(executor.negotiations).hasValue(1);
    }

    @Test
    void boundsPayloadAndDoesNotLeakFailureDetails() {
        byte[] oversized = new byte[4097];
        A2aOutboundResponse response = adapter.handle(signed(A2aExposureAdapter.SEND_PATH, oversized,
                "nonce-big", NOW));

        assertThat(response.status()).isEqualTo(413);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"error\":\"payload_too_large\"}");
        assertThat(executor.executions).hasValue(0);
    }

    @Test
    void rejectsAnUnknownA2aProfilePathBeforeNegotiation() throws Exception {
        A2aOutboundResponse response = adapter.handle(signed("/a2a/v2/handshake",
                JSON.writeValueAsBytes(requirements()), "nonce-v2", NOW));

        assertThat(response.status()).isEqualTo(404);
        assertThat(executor.negotiations).hasValue(0);
        assertThat(executor.executions).hasValue(0);
    }

    private static A2aInboundRequest signed(String path, byte[] body, String nonce, Instant timestamp) {
        try {
            String canonical = "POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n"
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            return new A2aInboundRequest("POST", path, Map.of("X-Dvara-Key-Id", "caller",
                    "X-Dvara-Timestamp", timestamp.toString(), "X-Dvara-Nonce", nonce,
                    "X-Dvara-Signature", signature), body);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static ExternalProtocolRequirements requirements() {
        return new ExternalProtocolRequirements(RemoteExecutorHttpClient.PROTOCOL_VERSION,
                RemoteExecutorHttpClient.SCHEMA_VERSION, ExternalAssurance.BLACK_BOX,
                ExternalAccessMode.DVARAOS_MEDIATED, Set.of(ExternalCapability.EVENT_STREAMING));
    }

    private static final class FakeExecutor implements ExternalExecutorPort {
        private final AtomicInteger negotiations = new AtomicInteger();
        private final AtomicInteger executions = new AtomicInteger();
        @Override public ExternalExecutorDescriptor negotiate(ExternalProtocolRequirements requirements) {
            negotiations.incrementAndGet();
            return new ExternalExecutorDescriptor("fake-a2a", "1", "fake", "java-25",
                    "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                    requirements.protocolVersion(), requirements.schemaVersion(), requirements.assurance(),
                    requirements.accessMode(), requirements.capabilities(), false);
        }
        @Override public ExternalInvocationResult execute(ExternalInvocationRequest request,
                ExternalInteractionHandler interactions) { executions.incrementAndGet(); throw new AssertionError(); }
        @Override public void cancel(ExternalInvocationContext context, String reason) { throw new AssertionError(); }
    }
}
