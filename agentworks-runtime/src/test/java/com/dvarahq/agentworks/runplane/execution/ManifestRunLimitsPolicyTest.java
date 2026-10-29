package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRow;
import com.dvarahq.agentworks.domain.run.RunLimits;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestRunLimitsPolicyTest {
    @Test
    void capturedLimitsCanTightenButNeverRelaxOperatorCeilings() {
        var policy = new ManifestRunLimitsPolicy(new RunLimits(1_000L, 50L, 20L, 60_000L,
                new BigDecimal("10.000000")));
        String manifest = "{\"manifestVersion\":7,\"limits\":{\"maxSteps\":100,\"maxToolCalls\":5,"
                + "\"maxRuntime\":\"2m\",\"maxCostUsd\":3}}";

        RunLimits limits = policy.limitsFor(release(manifest));

        assertThat(limits).isEqualTo(new RunLimits(1_000L, 50L, 5L, 60_000L,
                new BigDecimal("3.000000")));
    }

    @Test
    void retainedManifestUsesConfiguredCeilings() {
        RunLimits ceilings = new RunLimits(100L, 10L, 5L, 1_000L, new BigDecimal("2.000000"));
        assertThat(new ManifestRunLimitsPolicy(ceilings).limitsFor(release("{\"manifestVersion\":6}")))
                .isEqualTo(ceilings);
    }

    private static ReleaseRow release(String manifest) {
        return new ReleaseRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "r",
                manifest, "sha256:" + "a".repeat(64), Instant.EPOCH);
    }
}
