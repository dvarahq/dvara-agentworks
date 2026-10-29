package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.DurableRunStore;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunTransaction;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #199 review, low 6: the claim quarantine is honored by contract, not only by the reference store. The engine
 * refuses a scope that names another node's compatibility identity instead of silently replacing it. The shipped
 * stores use {@code CompatibleClaimStore}; a direct third-party implementation remains responsible for reading the
 * required identity itself (#201, #210).
 */
class ClaimCompatibilityContractTest {
    private final EngineHarness harness = new EngineHarness(EngineHarness.CHECKPOINTING, EngineHarness.EXECUTORS);

    @Test
    void theEngineRefusesAScopeThatNamesAnotherCompatibilityIdentity() {
        harness.engine.enqueue(harness.workspace, harness.run(), "system:test");

        assertThatThrownBy(() -> harness.engine.claimNext("worker-1",
                harness.scope().compatibleWith("agentworks-another-build"), EngineHarness.LEASE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compatibility identity");
        // Its own identity, named or not, claims as before.
        assertThat(harness.engine.claimNext("worker-1", harness.scope().compatibleWith(harness.engine.compatibility()),
                EngineHarness.LEASE)).isPresent();
    }

    // #201 low 2, with #210's corrected extension contract: a direct store that honors quarantines reads the required
    // identity itself. The default reporting overload also checks it before delegating, but the basic interface method
    // cannot force an arbitrary implementation to do so. The shipped stores use CompatibleClaimStore below.
    @Test
    void theStoreContractRefusesAScopeWithoutACompatibilityIdentity() {
        var reached = new AtomicBoolean();
        // A HashSet, like a store's own index, answers false for a null identity rather than failing.
        java.util.Set<String> quarantined = new java.util.HashSet<>(java.util.List.of("agentworks-build"));
        DurableRunStore honoring = new DurableRunStore() {
            @Override
            public <T> T inTransaction(WorkspaceId workspace, Function<RunTransaction, T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> Optional<T> claimNext(ClaimScope scope, BiFunction<RunRecord, RunTransaction, T> work) {
                // Skips every run while the claiming node's identity is quarantined; else there is nothing to claim.
                if (quarantined.contains(scope.requiredCompatibility())) return Optional.empty();
                reached.set(true);
                return Optional.empty();
            }
        };
        ClaimScope unnamed = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS);

        assertThat(unnamed.namesCompatibility()).isFalse();
        assertThat(unnamed.compatibility()).isNull();
        assertThatThrownBy(unnamed::requiredCompatibility).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compatibility identity");
        assertThatThrownBy(() -> honoring.claimNext(unnamed, (run, tx) -> run))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compatibility identity");
        assertThatThrownBy(() -> honoring.claimNext(unnamed, selected -> { }, (run, tx) -> run))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compatibility identity");
        assertThat(reached).isFalse();
        assertThat(honoring.claimNext(unnamed.compatibleWith("agentworks-build"), (run, tx) -> run)).isEmpty();
        assertThat(reached).isFalse();
        assertThat(honoring.claimNext(unnamed.compatibleWith("agentworks-other"), selected -> { }, (run, tx) -> run))
                .isEmpty();
        assertThat(reached).isTrue();
    }

    // #206 low, as revised by the PR #208 review: the record's plain accessor stays nullable, so a record copy of a
    // scope without an identity works; the guarantee lives in the store path instead. A store built on
    // CompatibleClaimStore implements only claimCompatible, which receives the resolved identity, and both claim
    // methods refuse a scope without one before that store runs, whichever its caller uses.
    @Test
    void aStoreBuiltOnTheBaseClassNeverClaimsWithoutACompatibilityIdentity() {
        var identities = new java.util.ArrayList<String>();
        DurableRunStore store = new com.dvarahq.agentworks.domain.run.CompatibleClaimStore() {
            @Override
            public <T> T inTransaction(WorkspaceId workspace, Function<RunTransaction, T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            protected <T> Optional<T> claimCompatible(ClaimScope scope, String compatibility,
                    java.util.function.Consumer<java.util.UUID> selected, BiFunction<RunRecord, RunTransaction, T> work) {
                identities.add(compatibility);
                return Optional.empty();
            }
        };
        ClaimScope unnamed = ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS);

        // The plain accessor is a plain component: a record copy of an unnamed scope is an unnamed scope.
        assertThat(unnamed.compatibility()).isNull();
        assertThat(new ClaimScope(unnamed.workspace(), unnamed.manifestVersions(), unnamed.excludedRunIds(),
                unnamed.executorTypes(), unnamed.compatibility())).isEqualTo(unnamed);
        assertThatThrownBy(() -> store.claimNext(unnamed, (run, tx) -> run))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compatibility identity");
        assertThatThrownBy(() -> store.claimNext(unnamed, selected -> { }, (run, tx) -> run))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compatibility identity");
        assertThat(identities).isEmpty();

        assertThat(store.claimNext(unnamed.compatibleWith("agentworks-build"), (run, tx) -> run)).isEmpty();
        assertThat(store.claimNext(unnamed.compatibleWith("agentworks-other"), selected -> { }, (run, tx) -> run))
                .isEmpty();
        assertThat(identities).containsExactly("agentworks-build", "agentworks-other");
    }

    // The reference stores are built on it, so their claims cannot skip the identity whichever method is called.
    @Test
    void theReferenceStoresAreBuiltOnTheBaseClass() {
        assertThat(com.dvarahq.agentworks.domain.run.CompatibleClaimStore.class)
                .isAssignableFrom(InMemoryDurableRunStore.class)
                .isAssignableFrom(com.dvarahq.agentworks.storage.run.JdbcDurableRunStore.class);
    }
}
