package com.dvarahq.agentworks.tck;

import com.dvarahq.agentworks.sdk.extension.ProviderInvocationContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderTckTest {
    @Test void deterministicFixturePassesAllPortableChecks() {
        Set<String> capabilities = Set.of("cancel", "cleanup", "exact-context");
        ProviderTckReport report = ProviderTck.verify(
                ready -> new DeterministicProviderProbe(ready, capabilities), capabilities);
        assertThat(report.passed()).isTrue();
        assertThat(report.checks()).containsExactly("capability-advertisement", "tenancy-and-provenance",
                "redaction", "cancellation", "graceful-degradation", "lifecycle-cleanup");
    }

    @Test void fixtureThatDoesNotReleaseResourcesFailsTheTck() {
        Set<String> capabilities = Set.of("cleanup");
        assertThatThrownBy(() -> ProviderTck.verify(ready -> new ProviderProbe() {
            private final DeterministicProviderProbe delegate = new DeterministicProviderProbe(ready, capabilities);
            @Override public Set<String> capabilities() { return delegate.capabilities(); }
            @Override public boolean ready() { return delegate.ready(); }
            @Override public boolean closed() { return false; }
            @Override public ProviderObservation invoke(ProviderInvocationContext context, byte[] input) {
                return delegate.invoke(context, input);
            }
            @Override public void close() { }
        }, capabilities)).isInstanceOf(AssertionError.class)
                .hasMessage("ready provider did not release its resources");
    }
}
