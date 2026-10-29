package com.dvarahq.agentworks.runplane.admission;

import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionPolicy.OverloadOutcome;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionPolicy.PlacementCapabilities;
import com.dvarahq.agentworks.storage.lifecycle.JdbcRunAdmissionRepository.AdmissionSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunAdmissionPolicyTest {
    private static final RunLimits BOUNDED = new RunLimits(100L, 10L, 5L, 1_000L, new BigDecimal("2.000000"));

    @Test
    void admitsWithinEveryBoundAndRejectsBeforeAllocationAtEachBoundary() {
        RunAdmissionPolicy policy = policy(OverloadOutcome.REJECTED);
        AdmissionSnapshot empty = snapshot(0, 0, 0, 0, 0, 0, 0, 0, "0");

        assertThat(policy.evaluate(empty, request(Map.of())).accepted()).isTrue();
        assertThat(policy.evaluate(snapshot(2, 0, 0, 0, 0, 0, 0, 0, "0"), request(Map.of())).code())
                .isEqualTo("WORKSPACE_OUTSTANDING_LIMIT");
        assertThat(policy.evaluate(snapshot(0, 1, 0, 0, 0, 0, 0, 0, "0"), request(Map.of())).code())
                .isEqualTo("DEPLOYMENT_OUTSTANDING_LIMIT");
        assertThat(policy.evaluate(snapshot(0, 0, 2, 0, 0, 0, 0, 0, "0"), request(Map.of())).code())
                .isEqualTo("WORKSPACE_QUEUE_FULL");
        assertThat(policy.evaluate(snapshot(0, 0, 0, 1, 0, 0, 0, 0, "0"), request(Map.of())).code())
                .isEqualTo("DEPLOYMENT_QUEUE_FULL");
        assertThat(policy.evaluate(snapshot(0, 0, 0, 0, 1, 0, 0, 0, "0"), request(Map.of())).code())
                .isEqualTo("SANDBOX_CAPACITY_EXHAUSTED");
        assertThat(policy.evaluate(snapshot(0, 0, 0, 0, 0, 1, 0, 0, "0"), request(Map.of())).code())
                .isEqualTo("PLACEMENT_CAPACITY_EXHAUSTED");
        assertThat(policy.evaluate(snapshot(0, 0, 0, 0, 0, 0, 2, 0, "0"), request(Map.of())).code())
                .isEqualTo("ADMISSION_RATE_LIMITED");
        assertThat(policy.evaluate(snapshot(0, 0, 0, 0, 0, 0, 0, 950, "0"), request(Map.of())).code())
                .isEqualTo("TOKEN_BUDGET_EXHAUSTED");
        assertThat(policy.evaluate(snapshot(0, 0, 0, 0, 0, 0, 0, 0, "4"), request(Map.of())).code())
                .isEqualTo("COST_BUDGET_EXHAUSTED");
    }

    @Test
    void explicitPlacementConstraintsFailClosedAndSaturationCanDefer() {
        RunAdmissionPolicy policy = policy(OverloadOutcome.DEFERRED);
        AdmissionSnapshot saturated = snapshot(2, 0, 0, 0, 0, 0, 0, 0, "0");

        assertThat(policy.evaluate(saturated, request(Map.of())).outcome()).isEqualTo("DEFERRED");
        assertThat(policy.evaluate(snapshot(0, 0, 0, 0, 0, 0, 0, 0, "0"),
                request(Map.of("securityZone", "zone-b")))).satisfies(result -> {
                    assertThat(result.outcome()).isEqualTo("REJECTED");
                    assertThat(result.code()).isEqualTo("PLACEMENT_INCOMPATIBLE");
                });
    }

    private static RunAdmissionPolicy policy(OverloadOutcome outcome) {
        return new RunAdmissionPolicy("test-v1", 2, 1, 2, 1, 2, Duration.ofMinutes(1), 1,
                Map.of("SHARED", 1L, "ISOLATED", 1L, "DEDICATED", 1L), 1_000L,
                new BigDecimal("5.000000"),
                new PlacementCapabilities(Set.of("zone-a"), Set.of("us"), Set.of("java"), Set.of("trusted")),
                outcome);
    }

    private static RunAdmissionPolicy.Request request(Map<String, String> constraints) {
        return new RunAdmissionPolicy.Request(true, true, true, "SHARED", BOUNDED, constraints);
    }

    private static AdmissionSnapshot snapshot(long workspace, long deployment, long workspaceQueue,
            long deploymentQueue, long sandbox, long placement, long roots, long tokens, String cost) {
        return new AdmissionSnapshot(workspace, deployment, workspaceQueue, deploymentQueue, sandbox, placement,
                roots, tokens, new BigDecimal(cost));
    }
}
