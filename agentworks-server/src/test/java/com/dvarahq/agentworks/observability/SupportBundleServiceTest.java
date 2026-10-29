package com.dvarahq.agentworks.observability;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportBundleServiceTest {
    @Test
    void bundleIsTenantBoundBoundedAndContainsOnlyRedactedCorrelationFacts() {
        WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
        String trace = "0123456789abcdef0123456789abcdef";
        Instant created = Instant.parse("2026-10-19T23:59:00Z");
        var snapshot = new SupportBundleRepository.Snapshot(Map.of("FAILED", 1L), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), 0, null, null,
                new SupportBundleRepository.Correlation(trace,
                        List.of(new SupportBundleRepository.RunIdentity(UUID.randomUUID(), UUID.randomUUID(),
                                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "FAILED", "MODEL_FAILED",
                                "spring-ai", created)),
                        List.of(new SupportBundleRepository.EventIdentity(UUID.randomUUID(), 1, "RUN_FAILED",
                                "0123456789abcdef", created)), List.of(), List.of()));
        java.util.concurrent.atomic.AtomicReference<String> requested = new java.util.concurrent.atomic.AtomicReference<>();
        var limits = new AgentWorksObservabilityProperties.SupportBundle();
        limits.setMaxEvents(7);
        var service = new SupportBundleService((actualWorkspace, actualTrace, maximum) -> {
            assertThat(actualWorkspace).isEqualTo(workspace);
            assertThat(maximum).isEqualTo(7);
            requested.set(actualTrace);
            return snapshot;
        }, limits,
                Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC));

        String json = new String(service.create(workspace, trace.toUpperCase()), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(requested).hasValue(trace);
        assertThat(json).contains("2026-10-20T00:00:00Z", "2026-10-19T23:59:00Z", trace,
                "workspaceDigest", "payloads");
        assertThat(json).doesNotContain(workspace.value().toString(), "secret-canary", "prompt-canary");
        assertThat(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(limits.getMaxBytes());
    }

    @Test
    void invalidTraceIdsAreRefusedBeforeAnyRead() {
        var limits = new AgentWorksObservabilityProperties.SupportBundle();
        var service = new SupportBundleService((workspace, trace, maximum) -> {
            throw new AssertionError("invalid input must not reach the repository");
        }, limits, Clock.systemUTC());
        assertThatThrownBy(() -> service.create(new WorkspaceId(UUID.randomUUID()), "not-a-trace"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32");
    }
}
