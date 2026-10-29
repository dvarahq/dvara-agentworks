package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.Objects;

/** Bounded, payload-free recovery diagnostics for Actuator health (#44). */
public final class OutboxRecoveryHealthIndicator implements HealthIndicator {
    private final ControlPlaneOutboxDispatcher dispatcher;
    private final String consumer;

    public OutboxRecoveryHealthIndicator(ControlPlaneOutboxDispatcher dispatcher, String consumer) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
    }

    @Override
    public Health health() {
        try {
            long backlog = dispatcher.pending(consumer);
            long quarantined = dispatcher.quarantined(consumer);
            return Health.up().withDetail("consumer", consumer).withDetail("backlog", backlog)
                    .withDetail("quarantined", quarantined)
                    .withDetail("oldestPendingAgeSeconds", dispatcher.oldestPendingAgeSeconds(consumer))
                    .withDetail("recoveryState", quarantined == 0 ? "CONVERGING" : "ATTENTION_REQUIRED").build();
        } catch (RuntimeException unavailable) {
            return Health.down(unavailable).withDetail("consumer", consumer).build();
        }
    }
}
