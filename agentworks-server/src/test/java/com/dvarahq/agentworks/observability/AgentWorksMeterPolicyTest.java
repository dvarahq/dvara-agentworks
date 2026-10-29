package com.dvarahq.agentworks.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorksMeterPolicyTest {
    @Test
    void rejectsIdentityLabelsAndCapsDynamicDimensions() {
        var registry = new SimpleMeterRegistry();
        var policy = new AgentWorksMeterPolicy(2);
        registry.config().meterFilter(policy);

        Counter.builder("agentworks.safe").tag("outcome", "success").register(registry).increment();
        Counter.builder("agentworks.secret").tag("agentworks.workspace.id", "customer-a").register(registry).increment();
        Counter.builder("agentworks.routes").tag("agentworks.route", "one").register(registry);
        Counter.builder("agentworks.routes").tag("agentworks.route", "two").register(registry);
        Counter.builder("agentworks.routes").tag("agentworks.route", "three").register(registry);

        assertThat(registry.find("agentworks.safe").counter()).isNotNull();
        assertThat(registry.find("agentworks.secret").counter()).isNull();
        assertThat(registry.find("agentworks.routes").counters()).hasSize(2);
        assertThat(policy.denied()).isEqualTo(2);
    }
}
