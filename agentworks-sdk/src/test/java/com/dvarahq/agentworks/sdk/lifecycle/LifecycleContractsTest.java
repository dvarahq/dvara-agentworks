package com.dvarahq.agentworks.sdk.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LifecycleContractsTest {
    @Test void postgresqlReportSeparatesQuiescenceFromLocalCacheState() {
        QuiescenceReport blocked = new QuiescenceReport(UUID.randomUUID(), UUID.randomUUID(), 4, Instant.now(),
                QuiescenceReport.Authority.POSTGRESQL, Map.of("RUN", 1L, "INTERACTION", 0L));
        assertThat(blocked.quiescent()).isFalse();
        assertThat(new QuiescenceReport(blocked.workspaceId(), blocked.cohortId(), 5, Instant.now(),
                QuiescenceReport.Authority.POSTGRESQL, Map.of()).quiescent()).isTrue();
    }

    @Test void terminalCohortsCannotBeResurrectedAndMigrationIsExplicitlyUnavailable() {
        assertThatThrownBy(() -> new CohortTransition(UUID.randomUUID(), RuntimeCohortRef.State.RETIRED,
                RuntimeCohortRef.State.ROUTABLE, 3, Instant.now(), "operator"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot become routable");
        assertThatThrownBy(() -> DrainPolicy.parse("migrate_when_compatible"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CAPABILITY_UNAVAILABLE");
    }

    @Test void lineageDistinguishesRootFromAuthorityIssuedContinuation() {
        UUID root = UUID.randomUUID();
        assertThat(new RunLineage(root, root, null, RunLineage.Kind.ROOT).parent()).isEmpty();
        UUID child = UUID.randomUUID();
        assertThat(new RunLineage(child, root, root, RunLineage.Kind.CHILD).parent()).contains(root);
        assertThatThrownBy(() -> new RunLineage(child, root, null, RunLineage.Kind.CHILD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ordinaryCoreRouteIsOneFullWeightImmutableTarget() {
        RoutingTargetRef target = new RoutingTargetRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10_000);
        assertThat(target.ordinaryCoreTarget()).isTrue();
    }
}
