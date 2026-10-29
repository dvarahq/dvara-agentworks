package com.dvarahq.agentworks.starter;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Configuration-only diagnostics. Health never resolves credentials or contacts an external service. */
public final class AgentWorksClientHealthIndicator implements HealthIndicator {
    private final AgentWorksClientProperties properties;
    private final boolean resolverPresent;

    public AgentWorksClientHealthIndicator(AgentWorksClientProperties properties, boolean resolverPresent) {
        this.properties = properties;
        this.resolverPresent = resolverPresent;
    }

    @Override public Health health() {
        boolean referenceConfigured = properties.getCredentialRef() != null
                && !properties.getCredentialRef().isBlank();
        String state = referenceConfigured && !resolverPresent ? "CREDENTIAL_RESOLVER_ABSENT" : "CONFIGURED";
        return Health.up().withDetail("state", state)
                .withDetail("baseUrlConfigured", properties.getBaseUrl() != null)
                .withDetail("credentialReferenceConfigured", referenceConfigured)
                .withDetail("connectivity", "NOT_PROBED").build();
    }
}
