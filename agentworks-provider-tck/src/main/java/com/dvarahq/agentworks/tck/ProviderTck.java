package com.dvarahq.agentworks.tck;

import com.dvarahq.agentworks.sdk.extension.CancellationSignal;
import com.dvarahq.agentworks.sdk.extension.ProviderInvocationContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reusable 1.0 provider TCK. Provider-specific adapters feed their native invocation into {@link ProviderProbe}.
 * Failures are deterministic {@link AssertionError}s suitable for JUnit, Maven, or a standalone verifier.
 */
public final class ProviderTck {
    private static final byte[] CANARY = "secret-canary-never-report".getBytes(StandardCharsets.UTF_8);
    private ProviderTck() { }

    public static ProviderTckReport verify(ProviderProbeFactory factory, Set<String> advertisedCapabilities) {
        List<String> checks = new ArrayList<>();
        ProviderInvocationContext first = context(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("10000000-0000-0000-0000-000000000001"), CancellationSignal.mutable());
        ProviderInvocationContext secondTenant = context(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("10000000-0000-0000-0000-000000000002"), CancellationSignal.mutable());

        ProviderProbe readyProbe = factory.create(true);
        try (ProviderProbe probe = readyProbe) {
            require(probe.ready(), "ready fixture reports unavailable");
            require(probe.capabilities().equals(Set.copyOf(advertisedCapabilities)), "capability advertisement drift");
            checks.add("capability-advertisement");
            ProviderObservation firstResult = probe.invoke(first, CANARY.clone());
            ProviderObservation secondResult = probe.invoke(secondTenant, CANARY.clone());
            exact(first, firstResult); exact(secondTenant, secondResult);
            require(!firstResult.workspaceId().equals(secondResult.workspaceId()), "tenant identity collapsed");
            checks.add("tenancy-and-provenance");
            require(!firstResult.toString().contains(new String(CANARY, StandardCharsets.UTF_8)), "secret canary leaked");
            checks.add("redaction");
        }
        require(readyProbe.closed(), "ready provider did not release its resources");

        CancellationSignal.Mutable cancellation = CancellationSignal.mutable();
        cancellation.cancel();
        ProviderInvocationContext cancelled = context(UUID.randomUUID(), UUID.randomUUID(), cancellation);
        ProviderProbe cancellationProbe = factory.create(true);
        try (ProviderProbe probe = cancellationProbe) {
            require(probe.invoke(cancelled, new byte[0]).outcome() == ProviderObservation.Outcome.CANCELLED,
                    "cancelled call was not cancelled");
            checks.add("cancellation");
        }
        require(cancellationProbe.closed(), "cancellation provider did not release its resources");

        ProviderProbe unavailableProbe = factory.create(false);
        try (ProviderProbe probe = unavailableProbe) {
            require(!probe.ready(), "unavailable fixture reports ready");
            require(probe.invoke(first, new byte[0]).outcome() == ProviderObservation.Outcome.UNAVAILABLE,
                    "unavailable provider did not degrade explicitly");
            checks.add("graceful-degradation");
        }
        require(unavailableProbe.closed(), "unavailable provider did not release its resources");
        checks.add("lifecycle-cleanup");
        return new ProviderTckReport(true, checks);
    }

    private static ProviderInvocationContext context(UUID workspace, UUID run, CancellationSignal signal) {
        return new ProviderInvocationContext(workspace, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), run,
                "routing-generation-1", "target-1", signal);
    }

    private static void exact(ProviderInvocationContext expected, ProviderObservation actual) {
        require(expected.workspaceId().equals(actual.workspaceId()), "workspace provenance changed");
        require(expected.releaseId().equals(actual.releaseId()), "release provenance changed");
        require(expected.deploymentRevisionId().equals(actual.deploymentRevisionId()), "revision provenance changed");
        require(expected.cohortId().equals(actual.cohortId()), "cohort provenance changed");
        require(expected.runId().equals(actual.runId()), "run provenance changed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
