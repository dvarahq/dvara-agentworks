package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.credential.conformance.CredentialProviderConformanceHarness;
import com.dvarahq.agentworks.credential.conformance.CredentialProviderConformanceSuite;
import com.dvarahq.agentworks.credential.conformance.DeterministicFakeCredentialProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class DeterministicFakeCredentialProviderConformanceTest extends CredentialProviderConformanceSuite {

    @Override
    protected CredentialProviderConformanceHarness createHarness() {
        return new DeterministicFakeCredentialProvider();
    }

    @Test
    void publishedTckAndFakeAreUsableFromAConsumerModule() {
        try (DeterministicFakeCredentialProvider provider = new DeterministicFakeCredentialProvider()) {
            assertThat(provider.provider()).isSameAs(provider);
        }
    }
}
